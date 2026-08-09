---
okf_version: 0.2
type: concept
title: Kotlin engine facade and native submodule boundary
description: The JNI/CLI runtime boundary, capability flow, native lifecycle, and build contract between Kotlin and qwen3-tts-cpp.
tags:
  - architecture
  - native
  - jni
  - qwen3-tts-cpp
generated: 2026-08-03
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
  - /external/CMakeLists.txt
  - /.gitmodules
  - /scripts/build-native.ps1
  - /scripts/build-native.sh
  - /docs/BUILD.md
  - /docs/OKF.md
---

# Kotlin engine facade and native submodule boundary

`QwenEngine` is the runtime boundary used by the view models. It hides native library discovery and pointer management behind operations for model loading, capability queries, synthesis, streaming, speaker-embedding extraction, and ICL-prompt extraction. The feature-level state and artifact decisions around those operations belong to [Compose screens and view-model state](/architecture/ui-composition-and-state.md) and [voice data and capability flows](/architecture/voice-data-and-capability-flows.md).

## Observed Kotlin-side contract

The JNI declarations in `QwenEngine.kt` cover:

- native context creation/free and model loading;
- ICL prompt-encoder loading;
- backend selection and compiled-backend discovery;
- non-streaming and streaming synthesis;
- speaker embedding and ICL prompt extraction;
- available-speaker, last-error, and model-capability queries.

The public Kotlin facade translates these into `NativeOperationResult`, `NativeResult`, `NativeCapabilities`, and `NativeAudioChunk`. It also maps human language names to engine language IDs and maps backend preferences (`Auto`, `Cpu`, `Cuda`) to native constants.

## Load and fallback sequence

The observed `loadDetailed` sequence is:

1. Validate that the model directory exists.
2. Release any existing native pointer and record the requested model directory/name.
3. Resolve a native root from the working directory, development build locations, or `jna.library.path`.
4. Load GGML base/backend dependencies and then `qwen3_tts.dll` on Windows or `libqwen3_tts.so` on Linux.
5. Select the requested backend, initialize a native pointer, and load the model.
6. If native loading, initialization, a model load, or a newer JNI symbol fails, look for the native CLI and switch to CLI fallback when it is available.

Fallback is a runtime mode inside `QwenEngine`, not a separate view-model implementation. In that mode, synthesis and extraction are assembled as CLI processes with temporary output paths. The facade reports streaming as unavailable and infers capabilities from the selected model name rather than querying native metadata.

## Capability boundary

When JNI is active, `getModelCapabilities()` returns native metadata: cloning support, named-speaker support, instruction support, embedding dimension, model kind, and speaker count. When CLI fallback is active, the same shape is synthesized from the model name. Callers must therefore treat the capability object as the source of truth for controls, while recognizing that fallback capabilities are weaker evidence. The UI and workflows consume this boundary in [StudioViewModel](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt) and [VoicesViewModel](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt).

## Native ownership and rebuild contract

`QwenEngine` owns a `nativePtr` and calls `nativeFree` from `release()`. `StudioViewModel` and `VoicesViewModel` each own an engine instance, so backend changes release both independently. The JVM cannot safely refresh an already loaded native library in place; the build guide therefore requires a native rebuild and process restart after changing the submodule, JNI declarations/implementations, native structs, or matching Kotlin signatures.

The parent CMake contract is concrete:

- `/external/qwen3-tts-cpp/` is a Git submodule declared in [`.gitmodules`](/.gitmodules).
- [`external/CMakeLists.txt`](/external/CMakeLists.txt) adds that submodule, disables its shared build, compiles `src/qwen3_tts_jni.cpp` into `qwen3_tts_shared`, and links it to the submodule's `qwen3_tts` target and JNI libraries.
- [`build-native.ps1`](/scripts/build-native.ps1) configures the external CMake project, builds `qwen3_tts_shared`, validates the expected GGML/native artifacts, and optionally copies them to the repository root. The shell script provides the analogous Linux flow.
- CUDA is a build-time option forwarded through CMake and a runtime backend preference exposed through settings.

In the inspected workspace, the submodule directory is present but empty, so files inside `qwen3-tts-cpp` and the implementation of `qwen3_tts_jni.cpp` were not directly verified. Statements about that implementation are limited to the parent CMake contract and the repository's existing OKF/build documentation.

## Architectural inference

`QwenEngine` functions as an anti-corruption layer: it converts native pointers, JNI symbols, CLI process conventions, and platform library names into a stable Kotlin operation surface. It is not a formal interface and does not remove all native policy from callers—for example, view models still choose when to load a talker versus ICL session—but it is the correct boundary for JNI symbol changes and native resource management.

Batch ASR validation follows the same boundary. `QwenAsrEngine` loads an optional Qwen3-ASR GGUF into a native pointer exposed by the existing `qwen3_tts.dll`; the ASR implementation is built from the `external/qwen3-asr-cpp` source submodule and shares the TTS GGML/backend targets. The desktop layer does not launch an ASR command or Python process.
