---
type: runtime-concept
title: JNI engine lifecycle
description: Native pointer ownership, serialized calls, reload behavior, and release paths at the Kotlin/JNI boundary.
tags: [runtime, jni, lifecycle, resources]
---

# JNI engine lifecycle

## Observed

- [`QwenEngine`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L22-L26) owns one `nativePtr`, the selected model directory/name, and a CLI-fallback flag. Its external methods include initialization, freeing, model loading, capability queries, synthesis, streaming callbacks, and voice-artifact extraction ([declarations](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L393-L434)).
- [`loadDetailed`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L452-L512) validates that the model path is a directory, releases any prior pointer, records the requested model, loads libraries, selects the backend, calls `nativeInit`, and calls `nativeLoadModels`. A failed model load reads `nativeGetLastError`, releases the pointer, and then attempts CLI fallback.
- [`loadIclPromptEncoderDetailed`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L514-L585) follows the same ownership sequence but calls a distinct native loader. A missing ICL JNI symbol is treated as a fallback-eligible failure.
- [`release`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L724-L737) calls `nativeFree` only for a non-zero pointer and then sets the pointer to zero. `StudioViewModel` and `VoicesViewModel` call release on backend changes and during `onCleared`; the Studio native executor is also closed during ViewModel teardown ([Studio cleanup](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt#L941-L952), [Voices cleanup](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt#L1841-L1856)).
- Studio native operations run on one daemon executor, and voice-preset operations run on another single-thread executor. This is an observed Kotlin-side serialization policy, not proof that the native engine itself is thread-safe.

## Inference / limits

- The intended ownership rule is one live pointer per `QwenEngine`, with `release` before replacement and at ViewModel disposal. The native destructor behavior is inferred from the `nativeFree(ptr)` contract and cannot be inspected because the submodule source is absent.
- `release()` does not clear the recorded model fields or the static library-loaded flag. A subsequent `loadDetailed` overwrites model fields and resets the fallback flag, while already-loaded process libraries remain loaded for the JVM lifetime.

## Related concepts

- [Native library loading](native-library-loading.md)
- [Model loading](model-loading.md)
- [CLI fallback](cli-fallback.md)
- [Runtime failure modes](runtime-failure-modes.md)

