---
okf_version: 0.2
type: operation
title: ASR concurrency testing
description: Process-isolated instrumentation for measuring loaded ASR engine concurrency without changing production batch validation.
status: active
tags: [operations, tests, agents, asr, cuda, concurrency, instrumentation]
generated:
  by: codex
  at: 2026-08-16T00:00:00Z
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/agent/AsrConcurrencyRunner.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenAsrEngine.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchValidation.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/main.kt
  - /scripts/run-asr-concurrency-profiles.ps1
  - /composeApp/src/desktopTest/kotlin/com/qwen/tts/studio/agent/AsrConcurrencyRunnerTest.kt
---

# ASR concurrency testing

The application exposes a separate `--agent-asr-concurrency` runner for testing whether multiple loaded ASR engines can safely and usefully process the same completed batch workload. It is an instrumentation path, not a change to the production batch validator: ordinary batch validation remains serialized and owns one loaded ASR engine.

## Run contract

The runner consumes a completed batch manifest and its direct child WAV chunks. It creates one fresh desktop application process per profile, loads each ASR engine once, keeps those engines loaded through warmup and measured calls, synchronizes parallel workers before each round, and writes `asr-concurrency-report.json`. It records engine backend evidence, device memory samples, call-level latency/transcripts, phase timing, model/manifest identity, and the gate decision.

The profile wrapper runs the matrix as isolated processes:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-asr-concurrency-profiles.ps1 `
  -ManifestPath D:\batch\manifest.json `
  -AsrModelPath D:\models\qwen3-asr\qwen3-asr-0.6b-f16.gguf `
  -OutputDirectory D:\temp\asr-concurrency `
  -NativeRuntimeRoot D:\work\qwen-tts-studio\artifacts\native-cuda-runtime `
  -Backend cuda
```

The wrapper writes one directory per profile plus `matrix-summary.json`. A single profile can be launched through the normal desktop entry point when a narrower probe is needed:

```powershell
.\gradlew.bat -PnativeRuntimeRoot=D:\work\qwen-tts-studio\artifacts\native-cuda-runtime :composeApp:run --no-daemon --args="--agent-asr-concurrency --profile parallel-cuda-2 --manifest D:\batch\manifest.json --asr-model D:\models\qwen3-asr\qwen3-asr-0.6b-f16.gguf --backend cuda --output-dir D:\temp\asr-concurrency\parallel-cuda-2"
```

`-PnativeRuntimeRoot` is optional but recommended when several DLL builds are present. It is forwarded to the application as `jna.library.path`, placing the selected runtime ahead of the working-directory copy; the resulting report fingerprints the loaded native library and dependencies when the runtime exposes them.

The runner fails closed for a missing manifest/model, incomplete or checksum-invalid chunk, unavailable native backend, missing CUDA residency evidence, worker timeout, failed call, or transcript mismatch against the serial baseline. It stops before further work if observed CUDA free memory falls below the configured safety floor.

## Profile matrix

| Profile | Engines | Work pattern | Purpose |
| --- | ---: | --- | --- |
| `serial-cpu-1` | 1 | CPU serial | CPU reference and functional control |
| `serial-cuda-1` | 1 | CUDA serial | GPU latency and memory reference |
| `parallel-cpu-2` | 2 | CPU parallel | Host-side concurrency control |
| `parallel-cuda-2` | 2 | CUDA parallel | Minimum GPU concurrency candidate |
| `parallel-cuda-3` | 3 | CUDA parallel | Scaling and memory pressure probe |
| `reuse-cuda-2` | 2 | CUDA parallel, repeated rounds | Loaded-engine reuse over repeated work |
| `tts-resident-asr-2` | 2 | TTS model resident, ASR parallel | ASR headroom while TTS remains loaded |
| `tts-active-asr-1` | 1 | TTS generation overlaps ASR | Mixed activity probe; not a concurrency approval |

The parallel CUDA profiles use the same selected windows and chunk order for the serial baseline and measured workers. The `tts-resident` and `tts-active` profiles are diagnostic: TTS has process-wide shared backend state, so their results do not establish that concurrent TTS generation is safe.

## Gate

Only an explicitly CUDA-backed profile marked eligible by the runner can satisfy the optimization gate. A candidate must have no measured failures or baseline transcript mismatches, at least 1.5 GiB and 15% of total device memory free at its minimum sample, at least 1.10x speedup versus the serial baseline, parallel p95 call latency no more than 1.50x the serial p95, and memory growth after all ASR engines are loaded no greater than 256 MiB or 10% of the post-load baseline. Missing memory evidence is `inconclusive`; it is never inferred from the requested backend flag.

The report can therefore describe a safe-looking exploratory profile as `status: passed` while `decision.eligibleForGate` is false. The matrix wrapper returns success only when the designated `parallel-cuda-2` profile is eligible and passes. No profile result changes the serialized production validator until the evidence is reviewed and the application policy is deliberately updated.

## Evidence interpretation

The report’s `engines` array proves how many native sessions were loaded and what each session reported. `memorySamples` separates device free/total memory from host memory. `phases` distinguishes warmup, serial baseline, and parallel work. `calls` retains the worker, round, chunk/window, duration, backend, and transcript comparison needed to reconcile timing with correctness. Model, manifest, runtime, and profile metadata make a result attributable to a particular native distribution and workload.

This instrumentation uses application seams, worker synchronization, native telemetry, and machine-readable reports. It does not open a window, send OS input, inspect pixels, or use computer-use interaction.
