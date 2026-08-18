---
okf_version: 0.2
type: operation
title: Headless batch verification
description: Headless application-boundary verification for UI actions, generation, validation, and WAV recombination.
status: active
tags: [operations, tests, agents, generation, verification]
generated:
  by: codex
  at: 2026-08-14T00:00:00Z
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/agent/AgentSmokeRunner.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/agent/AgentAsrInspectionRunner.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/agent/AgentTelemetry.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/agent/StudioUiInstrumentation.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchValidation.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenAsrEngine.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchIdentity.kt
- /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchGeneration.kt
- /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchAudio.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/BatchScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/BatchUiInstrumentation.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/main.kt
  - /composeApp/src/desktopTest/kotlin/com/qwen/tts/studio/agent/AgentSmokeRunnerTest.kt
  - /composeApp/src/desktopTest/kotlin/com/qwen/tts/studio/agent/StudioUiInstrumentationTest.kt
  - /composeApp/src/desktopTest/kotlin/com/qwen/tts/studio/screens/HeadedBatchUiInstrumentationTest.kt
  - /scripts/render-agent-proof.ps1
---

# Headless batch verification

The application has a headless batch-verification runner that exercises the same Studio batch actions used by the desktop UI. `StudioUiInstrumentation` is the code-level hand for the Batch surface: it invokes the exact generate, load-manifest, validate-all, combine, resume, regenerate-all, regenerate-chunk, validate-chunk, and cancel callback sequences used by `BatchScreen`, while recording before/after UI-state snapshots. Action evidence is marked complete only after the corresponding domain postcondition is true; failed validation therefore records `completed: false` with failed-chunk state instead of looking successful because a coroutine returned. The backend engine is injected and recorded separately. The runner writes a machine-readable `batch-verification-report.json` and is entered through the normal desktop application main class, so it does not create a Compose window, synthesize OS input, or require a display. The former `--agent-smoke` flag and `agent-report.json` filename remain compatibility aliases.

For a completed manifest with a small number of suspect ASR edges, `--agent-asr-inspect` runs the native ASR adapter against only the requested chunk indexes and writes `asr-inspection-report.json` with expected text, exact native transcript, similarity, timing, and backend-placement evidence. It is diagnostic and does not mutate the manifest. `--headless-batch-verify --resume --regenerate-chunks INDEX[,INDEX...]` invokes the instrumented per-row regeneration callback for those existing indexes, then runs the normal full validation and combine actions; a regenerated chunk is not accepted until it passes the full validation contract again.

## Headed semantic loop

The desktop test `HeadedBatchUiInstrumentationTest` mounts the real `App` with injected view models and drives the rendered Batch surface using Compose semantics actions. `BatchUiTestTags` identifies navigation and workflow controls, while the operation status and validation summary remain observable state rather than screenshot interpretation. `BatchScreenFileActions` replaces only the file-picker/save side effects in the test, so semantic clicks still pass through the same visible controls without opening an OS dialog. The test waits on `StudioViewModel.batchState` and then checks the corresponding rendered node; it does not sleep, click coordinates, inspect pixels, or use computer-use tooling. The desktop test dependency is Compose's `desktop.uiTestJUnit4` API, which is kept separate from native model evidence.

This headed loop complements rather than replaces the headless report. Headless evidence proves native/fake engine reuse, durable manifest state, validation findings, source round-trip, and recombination. Headed evidence proves that the Batch navigation and controls are actually reachable in the rendered app, that disabled/busy transitions are exposed, and that the UI reflects generation, validation, and combined-file completion. A future screenshot artifact may be captured from the Compose test surface, but screenshots are evidence only; semantic state and filesystem reports remain the acceptance contract.

For large manifests, the Batch surface keeps the chunk region bounded with a lazy list instead of composing every row into the page-level scroll. The list supports semantic search across chunk index, text, and voice, filters for all/needs-attention/complete/validated, and reports the visible count against the manifest total. Combine remains in the batch-operations toolbar so it is reachable without traversing the chunk list. `HeadedBatchUiInstrumentationTest.largeBatchUsesLazyListAndSemanticSearchToReachDistantChunks` generates 320 deterministic chunks, proves a distant row is not eagerly composed, searches to chunk 319, and filters the full batch to the needs-attention set.

## Fake-mode loop

Fake mode is the default and has no GGUF or native-library prerequisite:

```powershell
.\gradlew.bat :composeApp:run --args="--headless-batch-verify --output-dir D:\temp\qwen-headless-batch-verification"
```

The runner creates two deterministic chunks unless input is supplied. It writes `agent-input.txt`, a `batch\` directory containing `manifest.json`, text sidecars, and WAV chunks, and `combined.wav` at the output root. Exit code `0` means the report status is `passed`; exit code `1` means the workflow ran but failed a completion check; exit code `2` means the runner could not parse or start the request.

## Native-mode loop

After building the matching native library and selecting a model directory, run the same application entry point with native mode:

```powershell
.\gradlew.bat :composeApp:run --args="--headless-batch-verify --mode native --model-dir D:\models\qwen --model-name qwen3-tts-1.7b-base\q8_0.gguf --backend cpu --output-dir D:\temp\qwen-headless-batch-native"
```

Native mode requires an existing model directory and a working JNI runtime. The runner requires the loaded engine to report native execution and reusable buffered-session support; CLI fallback therefore fails the run instead of being mistaken for a one-load generation test. `--text-file PATH` uses the application paragraph packer for a fresh manifest. Repeated `--text TEXT` arguments are treated as explicit ordered chunks. `--speaker NAME`/`--voice NAME` and `--instruction=TEXT` carry named-speaker and prompt intent through the same replay request used by BatchScreen. `--chunk-characters N` selects a bounded lossless chunk plan, `--max-chunks N` limits a proof run to its first N planned chunks, and `--flat-output` writes the manifest/chunks/combined WAV directly under the output directory. The `scripts/run-chinese-vivian-batch.ps1` wrapper uses an auto profile: Chinese/Vivian selects the validated 80-character target, while an explicit `-ChunkCharacters N` remains available for comparison runs. Failed-chunk recovery retains a separate 40-character fallback.

To queue validation behind live generation, add `--queued-validate --validation-retries N`. The runner then uses the same instrumented BatchScreen action with one resident in-process ASR worker: each generated chunk is durably written and its index is queued while TTS continues. The bounded queue applies backpressure only when it is full. A validation failure is requeued for same-logical-chunk TTS retry after the current validation round; later chunks may already be durable. `--sequential-validate` and `--incremental-validate` are compatibility aliases, and direct callers can still select a blocking policy. This mode requires the installed ASR model for native runs unless `--skip-asr` is explicitly supplied; the latter still exercises deterministic queued admission. The final `validate-all` action performs a fresh deterministic/source audit and reuses persisted ASR admissions only when their signatures and ASR model fingerprint still match; otherwise it loads ASR for a full audit. This keeps long queued runs from re-reading thousands of already admitted chunks while preserving a fail-closed fallback.

The sequential report adds `sequentialValidation` and `validationRetries`. The durable per-chunk fields are the authoritative live-admission evidence: `validationPassed`, `validationMessage`, and `validationSignature`. A complete chunk is playable from the headed Batch list while later chunks are generating; no automatic audio-device playback is claimed by this instrumentation. The code-level action path and manifest are the proof surface, not screenshots or OS input.

Manifest-first replay uses `--manifest PATH --resume`. The manifest directory is the output directory, and the persisted chunk texts, model directory/name, backend, speaker, instruction, per-chunk voice map, and cap inputs become authoritative. Source text, chunk sizing, and generation overrides are not required; if supplied, conflicting values fail closed. A supplied `--text-file` is retained only as an optional source-round-trip check during validation.

When a durable chunk has failed generation or validation, use `--rechunk-failed --rechunk-characters N` with the same manifest-first replay. The runner invokes the BatchScreen recovery action, which stages the existing artifacts, writes a recoverable pre-rechunk manifest, splits failed source chunks into decimal logical labels such as `15.1` and `15.2`, and merges short failed tails with adjacent source text when safe before invoking the ordinary Resume action. The integer ordinal and zero-padded filenames remain internal storage keys; `displayIndex` is the stable user-facing recovery history. Complete chunks whose text is unchanged are not regenerated; a complete neighbor included in a merged retry is regenerated because its old WAV no longer matches the new text. Cap metadata is remapped only for unchanged complete rows, and the final validation/combine actions still gate the report. The Chinese/Vivian wrapper exposes the same path with `-Resume -RechunkFailed -RechunkCharacters 40`.

For a reliable chunk-size decision, run `scripts/probe-chinese-vivian-chunk-sizes.ps1`. It runs each candidate size for multiple independent rounds over stratified prefix, middle, and suffix windows, in a fresh timestamped session with no stale-manifest reuse, then writes `chunk-size-probe-report.json`. Each bounded sample proves that the runner's `agent-input.txt` is the exact prefix of the intended window, that manifest/report chunk counts agree and do not exceed the requested bound, and that `combined.wav` exists; `--max-chunks` is therefore an explicit sample bound rather than silent source truncation. The report counts complete/invalid chunks, native failures, aggregate-cap failures, ASR failures, generation/validation time, and device-memory high water. Lengths are reported in UTF-16 code units, matching Kotlin `String.length` and the application chunker. A candidate is recommended only when every round passes with no native, cap, or ASR failures; otherwise the probe fails closed instead of promoting a size from one lucky sample. The normal Chinese/Vivian profile is 80, with 40 reserved for targeted recovery.

Agent-generated requests enable the fresh-batch adaptive audio replan: the final manifest may contain more chunks than the source plan, and its expected count is the completion count used by the report. Adaptive replanning is intentionally bounded to a fresh output directory with a uniform voice map. If a manifest or chunk artifact already exists, or if the request selects/regenerates indexes or mixes per-chunk voices, the runner fails before native generation rather than renumbering durable artifacts. The manifest records `adaptiveAudioReplanVersion` and `adaptiveAudioReplanSourceChunks` when this occurs. The replan is lossless, preserves exact source sidecars, keeps context headroom, balances tiny tails, and merges short adjacent pieces only when the native aggregate floor remains safe.

For a deterministic proof when the optional probabilistic ASR edge check is not the acceptance target, `--skip-asr` omits the installed ASR model from the validation callback and records `asrValidationSkipped: true` in the report. This is explicit evidence scope, not a hidden fallback; without the flag, an ASR similarity failure fails the instrumented action.

When ASR is enabled, `--backend cuda` is also passed to the in-process ASR validator. The report's additive `asrEvidence` object records `backend`, `nativeName`, `gpuActive`, encoder/decoder device placement, device free/total bytes, validation-window count, chunk count, and total ASR elapsed time. A CUDA run is not considered GPU-backed merely because the request said `cuda`: the evidence must show an active CUDA backend and both weight-placement flags true. CPU remains the explicit default for ordinary validation, and a requested CUDA backend fails closed if native capability or device placement cannot be proven.

The repository includes a report renderer for screenshot-style evidence cards. It reads only the machine-readable report, manifest, filesystem artifacts, and `ffprobe` metadata:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\render-agent-proof.ps1 `
  -ReportPath D:\t1\batch-verification-report.json `
  -ManifestPath D:\t1\manifest.json `
  -OutputDirectory .\build\agent-proof
```

## Pass contract

The report is the completion boundary an agent should inspect. A pass requires all of the following:

- the Studio batch state reaches `COMPLETED` with every expected manifest chunk complete;
- the UI instrumentation records completed `generate` (or `resume`/selected `regenerate-chunk`), `load-manifest`, `validate-all`, and `combine` actions;
- a recovery run additionally records a completed `rechunk-failed` action between `load-manifest` and `resume`, and the resulting manifest contains decimal `displayIndex` children for each failed source chunk;
- deterministic validation reports no errors, every persisted chunk is `COMPLETE` before it can pass validation, every persisted chunk validation passes, and the generated WAVs have valid metadata/checksums;
- recombination creates a WAV outside the batch chunk files through the manifest action;
- each newly generated chunk persists the exact original-chunk `maxAudioTokens` input as `audioCapTokens.<index>`; sentence-boundary retry output must remain below that aggregate cap before persistence;
- when adaptive replanning is used, the final manifest's expected count is authoritative and its replan metadata identifies the source-plan count; every final chunk still carries its exact cap and source text;
- the engine loads once, reports reusable buffered-session support, and receives every normalized chunk in order; native sentence-boundary retry calls are allowed when the audio budget is approached;
- state events include generation completion, validation, and recombination completion.

The state-event trace is bounded for long batches, but it preserves the newest observation when the bound is reached. This keeps terminal `COMPLETED`/`FAILED` evidence available instead of allowing a high-chunk-count run to be reported incorrectly because only its early progress events were retained.

The extended desktop instrumentation tests also exercise resume, regenerate-all, per-chunk regeneration and validation, cancellation, and the fail-closed validation action contract. Regeneration invalidates the affected chunk's prior validation result; the agent must validate it again before treating the batch as fully validated.

Native generation may make additional sentence-boundary retry calls when a chunk approaches the audio-token ceiling. The completion contract counts durable manifest chunks, not raw engine calls; the report exposes the retry-inclusive generation call count separately.

Aggregate retry failure is intentionally fail-closed: a part that reaches its allocated remaining budget, or an aggregate result that reaches the original chunk budget, fails generation before a `COMPLETE` WAV is persisted. A native run can therefore leave earlier chunks durable while `generate` fails and `validate-all`/`combine` are not run; the manifest and report must be read together. The proof renderer handles this partial-failure state and missing combined WAV explicitly.

The report records the requested mode, native/fake execution mode, engine call counts, normalized generated texts, state transitions, validation findings, and absolute artifact paths. It is intentionally additive to the durable batch manifest; the manifest remains the source of truth for chunk provenance and audio integrity.

Native manifests also carry reproducibility provenance: TTS model file SHA-256/size/path, the loaded JNI library fingerprint, loaded GGML/CUDA dependency fingerprints, active/compiled backend, capability snapshot, and the ASR model fingerprint when ASR validation runs. The aggregate `nativeRuntimeFingerprint` supports quick comparison; individual dependency entries explain exactly what changed. The agent's recording wrapper delegates this identity so instrumentation does not hide it.

## Performance telemetry

`batch-verification-report.json` keeps `schemaVersion: 1` and adds an optional top-level `telemetry` object. Its monotonic `elapsedMillis` samples and bounded `phaseTimings`/`generation.records` expose run, load, validation, streaming, and buffered-call timing without making telemetry part of the engine contract. Generation summaries include successful/failed call counts, native-reported time when available, persisted synthesis audio duration, and wall-clock real-time factor (RTF); RTF is null when no valid audio duration is available. Validation summaries include run count, elapsed time, pass state, and bounded records. The legacy `agent-report.json` copy has the same contents for existing consumers.

Memory fields are deliberately domain-labeled. `telemetry.memory.deviceBackend` contains only `QwenEngine.backendMemory()` free/total observations and derived used-memory high-water values. `hostProcess` contains process RSS where the platform exposes it plus committed virtual memory, and `hostPhysical` contains host system-RAM free/total observations. Host physical memory is never reported or described as device memory. Raw samples and timing records retain their newest observation at a fixed bound; aggregate high-water/minimum fields continue to update after the raw-record bound is reached. Missing platform or backend metrics are serialized as `null` and do not fail the verification run.

ASR timing is reported separately from synthesis timing. `asrEvidence.totalElapsedMillis` covers the bounded prefix/suffix windows, while `telemetry.memory.deviceBackend` is the existing TTS/backend allocator observation. The ASR evidence's device free/total fields make it possible to distinguish ASR device residency from host physical RAM; the two domains must not be conflated.

## Evidence limits

Fake mode proves the application orchestration, code-level UI action sequence, state transitions, persistence, validation, and recombination without a model. It does not prove acoustic quality, JNI symbols, model compatibility, CUDA, or audio-device playback. Native mode adds those model/JNI, ASR-validation, and buffered-generation claims when it passes. This instrumentation intentionally does not exercise pixels, OS input, or Java Sound playback; those are separate concerns and must not be substituted for the agent action contract. The portable UI smoke operation remains the acceptance path for packaged startup when a human explicitly requests it; see [portable batch smoke testing](portable-batch-smoke-testing.md).

## Related concepts

- [Tests and verification](tests-and-verification.md)
- [Build and verification workflow](build-and-verification.md)
- [Reusable batched generation](../runtime/batched-generation.md)
- [Compose screens and view-model state](../architecture/ui-composition-and-state.md)
