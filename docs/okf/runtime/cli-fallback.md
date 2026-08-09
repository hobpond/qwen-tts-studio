---
type: runtime-concept
title: CLI fallback
description: Process-based recovery path used when JNI loading, model loading, or selected newer JNI symbols are unavailable.
tags: [runtime, cli, fallback, recovery]
---

# CLI fallback

## Observed

- [`enableCliFallbackOrFailure`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L587-L595) searches for `qwen3-tts-cli` after native library, native initialization, model loading, or ICL-loader failure. If found, the load operation returns success with `useCliFallback = true`; otherwise it returns the original error.
- [`resolveCliExe`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L378-L390) checks the resolved native root, several `external/build...` locations, and the parent project location. The process is launched with the root as its working directory and combined stdout/stderr.
- [`generateViaCli`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L853-L913) writes a unique WAV path under `.tts-cli`, passes model directory/name, text, language, and one of ICL prompt, embedding, reference WAV, or named speaker, then reads the WAV into a `FloatArray` and deletes the temporary output on return.
- Speaker embedding and ICL prompt extraction have dedicated CLI command paths ([embedding](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L915-L949), [ICL](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L951-L982)). Capabilities in fallback mode come from [filename inference](capability-detection.md), not native metadata.
- `generateStreaming` invokes the buffered CLI generator and returns a synthetic successful `NativeResult` with a 24 kHz rate when the CLI produces audio; it does not invoke the chunk callback. It returns an error saying streaming is unavailable only when the CLI generation itself fails ([branch](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L649-L667)).

CLI voice argument selection is ICL prompt, then embedding, then reference WAV, then named speaker. Studio currently supplies no reference-WAV argument from its Studio request, so its practical fallback order is ICL prompt, embedding, then named speaker. A CLI fallback request launches a fresh process and temporary WAV path per generation; it cannot provide a one-loaded-model/one-loaded-voice batch guarantee without a separate long-lived CLI protocol.

## Inference / limits

- Fallback is a compatibility bridge, not equivalent runtime behavior: it has no native chunk callbacks, no native speaker list, and no native capability metadata. The UI can therefore show a buffered result while streaming-specific progress/alignment is absent.
- The process output is read to completion and no timeout is set in these command paths. A hung or very slow CLI can therefore hold the calling coroutine until the process exits; this is an operational risk inferred from the observed `ProcessBuilder` code.
- Temporary files are deleted only on the successful WAV-read path for synthesis. A failed process can leave diagnostic output or an unused temporary WAV under `.tts-cli` unless later cleanup removes it.

## Related concepts

- [Native library loading](native-library-loading.md)
- [Model loading](model-loading.md)
- [Capability detection](capability-detection.md)
- [Synthesis, streaming, and playback](synthesis-streaming-playback.md)
- [Runtime failure modes](runtime-failure-modes.md)
