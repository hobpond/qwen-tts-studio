---
type: runtime-concept
title: Native runtime overview
description: Kotlin runtime orchestration around the Qwen3 native library, JNI engine state, CLI fallback, and audio delivery.
tags: [runtime, native, jni, qwen3]
---

# Native runtime overview

Qwen-TTS Studio keeps inference in the native Qwen3 C++ backend. The desktop Kotlin code owns library resolution, JNI calls, model/session state, capability-driven workflow selection, fallback process execution, and Java Sound playback.

## Observed

- [`QwenEngine.kt`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt) is the runtime boundary. It loads native dependencies, creates a native pointer, loads a model or ICL prompt encoder, queries capabilities, synthesizes, extracts voice artifacts, and releases the pointer.
- [`StudioViewModel.kt`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt) serializes native work on a daemon `QwenNativeThread`, then converts native audio into playback state. [`VoicesViewModel.kt`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt) has a separate serialized native executor for presets and Voice Lab previews.
- The normal path is: resolve and load native libraries, select CPU/CUDA/Auto, initialize the engine, load the selected model, query capabilities, synthesize through JNI, and send PCM to Java Sound. If native loading or some model/JNI operations fail, the wrapper may execute the sibling CLI instead.
- The checked-out `external/qwen3-tts-cpp/` directory is empty in this workspace even though [`external/CMakeLists.txt`](../../../external/CMakeLists.txt) treats it as a submodule and compiles `src/qwen3_tts_jni.cpp`. Native implementation details therefore are not directly inspected here.

## Related concepts

- [Native library loading](native-library-loading.md)
- [JNI engine lifecycle](jni-engine-lifecycle.md)
- [Model loading](model-loading.md)
- [Capability detection](capability-detection.md)
- [CLI fallback](cli-fallback.md)
- [Synthesis, streaming, and playback](synthesis-streaming-playback.md)
- [Runtime failure modes](runtime-failure-modes.md)
