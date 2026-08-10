---
type: runtime-concept
title: Runtime failure modes
description: Code-grounded symptoms, causes, and diagnostic boundaries across native loading, model sessions, fallback, synthesis, and playback.
tags: [runtime, diagnostics, failures, troubleshooting]
---

# Runtime failure modes

## Observed failure boundaries

| Symptom or boundary | Code-grounded cause and behavior | First diagnostic |
| --- | --- | --- |
| Main JNI library is missing | `resolveNativeRoot` cannot find the platform library, or a required GGML dependency is absent; the load error is retained and fallback is attempted ([loader](native-library-loading.md)). | Record OS, `user.dir`, `jna.library.path`, and the exact candidate paths from the error. |
| A new native method reports `UnsatisfiedLinkError` | Kotlin declarations and the loaded DLL/SO are out of sync. ICL loading explicitly reports this; ICL synthesis logs a rebuild hint and returns null ([JNI methods](jni-engine-lifecycle.md)). | Rebuild the native wrapper and restart the JVM so the process does not keep the stale library. |
| CUDA selection or loading fails | The requested backend may not be compiled, `ggml-cuda` may be missing, or CUDA runtime DLLs may not be found. Windows CUDA preloads are best effort; backend preference selection can still fail ([backend load](native-library-loading.md#observed)). | Compare requested preference, compiled backend mask, packaged DLLs, and CUDA runtime availability. |
| Model directory passes Setup but load fails | Kotlin validates only directory shape; native loading may reject missing tokenizer/talker files or an incompatible model ([model loading](model-loading.md)). | Preserve model directory/name and native last-error text. |
| Load reports success but JNI is not active | A CLI executable was found after native/model failure, so `useCliFallback` made the operation successful ([fallback selection](cli-fallback.md)). | Identify execution mode before interpreting capabilities or streaming behavior. |
| Named speakers or instructions disappear | Native capabilities may not report them, speaker enumeration may be empty, or fallback is using filename inference with `speakerCount = 0` ([capability handling](capability-detection.md)). | Record capability flags, model kind/name, and whether fallback was active. |
| Generation returns null or a failed result | Native pointer may be zero, JNI symbol may be stale, input artifacts may be invalid, or native synthesis returned an error. The high-level `generate` API logs native errors but returns only null to its caller ([generation](synthesis-streaming-playback.md)). | Inspect the operation context and native stderr/log output; distinguish model failure from UI error text. |
| Streaming produces no chunks | CLI fallback intentionally has no callbacks; cancellation can also return `false` from the Kotlin callback ([CLI streaming](cli-fallback.md)). | Check fallback mode and cancellation state before treating this as an inference defect. |
| Audio generation succeeds but playback fails | Java Sound cannot open a compatible mixer or `SourceDataLine`; playback errors are reported separately and the float buffer may remain saveable ([playback](synthesis-streaming-playback.md)). | Inspect mixer/device availability and try saving the WAV before retrying playback. |
| A process or native call appears stuck | CLI commands read merged process output and wait without an explicit timeout; native calls are serialized but have no Kotlin timeout in the inspected paths ([CLI limits](cli-fallback.md)). | Capture model/backend/mode context and avoid assuming the UI hang proves a native deadlock. |

## Inference / limits

- These are runtime boundaries observed in the Kotlin and build code, not a native crash taxonomy. The checked-out TTS and ASR source submodules are available for source inspection, while native crash and model behavior still require a matching build and smoke test.
- The most useful diagnostic tuple is: OS, Java/runtime launch location, model directory/name, backend preference, native artifact names, JNI versus CLI mode, operation (load, capability, synthesis, extraction, playback), and exact native/process error.

## Related concepts

- [Native runtime overview](native-runtime.md)
- [Native library loading](native-library-loading.md)
- [JNI engine lifecycle](jni-engine-lifecycle.md)
- [Model loading](model-loading.md)
- [Capability detection](capability-detection.md)
- [CLI fallback](cli-fallback.md)
- [Synthesis, streaming, and playback](synthesis-streaming-playback.md)
