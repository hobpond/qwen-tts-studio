---
okf_version: 0.2
type: concept
title: Project architecture and module boundaries
description: Code-grounded map of the desktop shell, Compose UI, workflow state, native facade, and C++ submodule boundaries.
tags:
  - architecture
  - modules
  - boundaries
generated: 2026-08-03
sources:
  - /docs/OKF.md
  - /composeApp/build.gradle.kts
  - /settings.gradle.kts
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/App.kt
  - /external/CMakeLists.txt
  - /external/qwen3-tts-cpp/
---

# Project architecture and module boundaries

This concept is the architecture entry point for the OKF v0.2 bundle. Read it with [UI composition and state](/architecture/ui-composition-and-state.md), [engine/native boundary](/architecture/engine-native-boundary.md), and [voice data and capability flows](/architecture/voice-data-and-capability-flows.md).

## Boundary map

| Area | Observed responsibility | Primary links |
| --- | --- | --- |
| `composeApp` Gradle module | The only included Gradle project; Kotlin Multiplatform has `commonMain`, `commonTest`, and a desktop JVM target. | [`settings.gradle.kts`](/settings.gradle.kts), [`composeApp/build.gradle.kts`](/composeApp/build.gradle.kts) |
| Desktop application shell | Creates the Compose window, theme state, navigation selection, shared view models, and the active screen. | [`main.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/main.kt), [`App.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/App.kt) |
| `screens/` | Compose presentation: reads `StateFlow`s, renders controls, owns screen-local transient UI state, and wires callbacks to view models. | [`SetupScreen.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/SetupScreen.kt), [`StudioScreen.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/StudioScreen.kt), [`BatchScreen.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/BatchScreen.kt), [`VoicesScreen.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoicesScreen.kt), [`VoiceLabScreen.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoiceLabScreen.kt) |
| `viewmodel/` | Workflow orchestration, asynchronous jobs, state flows, file persistence, audio playback/recording, capability interpretation, and calls into `QwenEngine`. | [`SettingsViewModel.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/SettingsViewModel.kt), [`StudioViewModel.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt), [`VoicesViewModel.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt) |
| `engine/` | Kotlin-side native runtime facade: library discovery/loading, JNI calls, native lifecycle, model capabilities, synthesis, extraction, and CLI fallback. | [`QwenEngine.kt`](/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt) |
| `commonMain/embedding/` | Pure speaker-embedding arithmetic and visualization summaries. It has no native or desktop file dependency and is covered by common tests. | [`EmbeddingArithmetic.kt`](/composeApp/src/commonMain/kotlin/com/qwen/tts/studio/embedding/EmbeddingArithmetic.kt), [`EmbeddingVisualization.kt`](/composeApp/src/commonMain/kotlin/com/qwen/tts/studio/embedding/EmbeddingVisualization.kt) |
| `external/` | CMake wrapper that adds the native submodule and builds the JNI shared library against the submodule's native target. | [`external/CMakeLists.txt`](/external/CMakeLists.txt), [`/.gitmodules`](/.gitmodules) |
| `scripts/` | Native build, run, packaging, and diagnostic entry points. These produce/copy runtime artifacts; they are not application workflow code. | [`build-native.ps1`](/scripts/build-native.ps1), [`run-compose.ps1`](/scripts/run-compose.ps1), [`package-windows.ps1`](/scripts/package-windows.ps1) |

## Runtime ownership

The observed call direction is:

```text
main.kt -> App.kt -> screen composable -> view model -> QwenEngine -> JNI wrapper -> qwen3-tts-cpp
                                      \-> common embedding functions (Voice Lab math/visualization)
```

`App.kt` creates one shared `SettingsViewModel`, `StudioViewModel`, and `VoicesViewModel` for the app composition. It passes the settings and voice view models into screens as needed. The app also reacts to the settings `appDir` and backend preference: app-directory changes are forwarded to `VoicesViewModel`, while backend changes release both engine instances before the next load.

## Observed facts

- The project is a desktop JVM application even though its Kotlin source set is organized as Kotlin Multiplatform. Desktop-only code contains the screens, view models, engine, Java Sound, file access, and JNI-facing declarations; the common source set contains theme and embedding code.
- The native TTS and ASR checkouts are Git submodules at `/external/qwen3-tts-cpp/` and `/external/qwen3-asr-cpp/`; both source trees are present in the inspected workspace. Their runtime behavior still depends on the native build and model-backed smoke tests.
- The CMake wrapper explicitly adds the submodule, compiles `qwen3-tts-cpp/src/qwen3_tts_jni.cpp`, and links the resulting shared library to the submodule's static `qwen3_tts` target.

## Architectural inference

The repository uses a pragmatic layered boundary rather than a strict clean-architecture package structure: screens are the presentation boundary, view models are feature/workflow owners, and `QwenEngine` is the anti-corruption facade around native details. This is an inference from imports, construction sites, and call direction; it is not represented by a formal interface or dependency-injection module.

The most important boundary to preserve is native ownership. Inference logic belongs in `qwen3-tts-cpp`; Kotlin should translate UI/workflow intent into engine calls and manage application state and artifacts around those calls. See [engine/native boundary](/architecture/engine-native-boundary.md).
