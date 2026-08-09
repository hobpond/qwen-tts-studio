---
type: runtime-concept
title: Synthesis, streaming, and playback
description: Audio data flow from JNI or CLI generation through chunk buffering, alignment state, Java Sound playback, and WAV export.
tags: [runtime, synthesis, streaming, audio, playback]
---

# Synthesis, streaming, and playback

## Observed

- Non-streaming [`QwenEngine.generate`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L608-L647) selects ICL-prompt synthesis when an ICL path is present, otherwise calls the regular JNI method with optional embedding/reference inputs and `NativeParams`. It returns only the successful native audio array to its caller.
- Native streaming accepts chunk and left-context durations, a collect-audio flag, and a callback carrying PCM samples, sample rate, sample/frame ranges, UTF-8 text-byte ranges, alignment kind, and confidence ([API](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L649-L722)). Returning `false` from the callback is the Kotlin cancellation signal.
- Studio appends chunks to a synchronized float buffer, records text spans from native UTF-8 byte offsets, and starts a `SourceDataLine` while generation continues ([chunk handling](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt#L703-L730), [streaming playback](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt#L749-L854)). The UI can seek, pause, resume, and highlight text using those spans.
- Playback converts normalized float samples to signed little-endian 16-bit PCM, chooses a compatible mixer when possible, and drains/closes the line at the end ([regular playback](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt#L524-L666)). Saved WAV output uses the same 16-bit mono conversion ([save path](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt#L492-L522)).
- Studio collects streamed chunks into `lastGeneratedAudio` after generation so the result can still be replayed or saved. In non-streaming mode it assumes the app’s 24 kHz default for the returned float array; streaming retains the native result sample rate when present.

## Inference / limits

- Streaming playback is intentionally producer/consumer behavior: the native callback produces chunks, while a separate playback job waits when no samples are currently available. The synchronization and cancellation fields make this behavior visible, but native callback threading is not directly inspectable without the missing submodule source.
- A Java Sound mixer/device failure can occur after synthesis succeeded. The error belongs to playback, not necessarily model inference; the generated buffer may still be available for saving.
- CLI fallback supplies buffered audio and a nominal 24 kHz result but no chunk callbacks, so alignment and low-latency playback are not equivalent to JNI streaming ([CLI behavior](cli-fallback.md)).

## Batch-generation boundary

Current Studio reloads the model before each generation, and the CLI fallback starts a fresh process per request. Neither path currently provides reusable model/voice state for long-form batching. The proposed extension is documented in [reusable batched generation](batched-generation.md): begin with sequential buffered JNI requests under one immutable session, retain verified audio format metadata, write atomic per-chunk WAVs, and recombine only a complete, format-compatible manifest. Streaming batch chunks remain out of scope until native `startSample`/`endSample`, overlap, channel, and PCM semantics are verified.

## Related concepts

- [Capability detection](capability-detection.md)
- [CLI fallback](cli-fallback.md)
- [JNI engine lifecycle](jni-engine-lifecycle.md)
- [Runtime failure modes](runtime-failure-modes.md)
