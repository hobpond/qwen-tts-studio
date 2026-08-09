---
okf_version: 0.2
type: concept
title: Compose screens and view-model state
description: How the desktop shell composes screens, how state crosses feature boundaries, and where asynchronous workflow ownership lives.
tags:
  - architecture
  - compose
  - viewmodel
  - state
generated: 2026-08-03
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/App.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/SetupScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/StudioScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/BatchScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoicesScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoiceLabScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/SettingsViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
---

# Compose screens and view-model state

This concept describes the UI/state half of the architecture. The native calls reached by these workflows are described in [engine/native boundary](/architecture/engine-native-boundary.md); the voice artifacts and capability rules are in [voice data and capability flows](/architecture/voice-data-and-capability-flows.md).

## Composition root

`App.kt` is the desktop composition root. It keeps the selected `Screen` in Compose state, constructs the three shared view models with `viewModel { ... }`, collects settings flows, and dispatches the active screen through a `when` expression. The welcome setup screen short-circuits the normal navigation until the settings view model says it can be dismissed.

Two cross-screen effects are architectural links:

- `appDir` from `SettingsViewModel` is passed to `VoicesViewModel.setAppDir`, causing voice presets to reload from the new application-data location.
- `backendPreference` changes call `releaseEngine()` on both `StudioViewModel` and `VoicesViewModel`; future workflow calls then load the requested backend.

`VoiceLabSessionState` is separate Compose state remembered by `App.kt` and passed to `VoiceLabScreen`. This means some Voice Lab form selections are screen/session state, while generated preview state, errors, and persisted presets remain in `VoicesViewModel`.

## Screen responsibilities

| Screen | Observed inputs and actions | Boundary note |
| --- | --- | --- |
| `SetupScreen` / `WelcomeSetupScreen` | Collect model/app directory, model list, download progress, backend preference, and compiled backend mask; invoke settings mutators and directory/download actions. | UI owns picker/dialog state; settings persistence and download work stay in `SettingsViewModel`. |
| `StudioScreen` | Collect `StudioUiState`, settings flows, and voice presets; refresh capabilities when model/backend inputs change; invoke generation, playback, seek, and save actions. | It wires a file-saver callback to `StudioViewModel.saveAudioToFile`; it does not synthesize directly and contains no batch-generation presentation. |
| `BatchScreen` | Presents text-file/manifest loading, chunk worklist, resume/regeneration, validation, and WAV recombination. | It uses the shared `StudioViewModel` because batch voice/model state and long-running work must survive switching between Synthesis and Batch tabs. |
| `VoicesScreen` | Collect presets, creation/error state, cloning capability, and recording state; refresh capabilities on model/backend changes; invoke record, extract, and delete actions. | Reference WAV selection is screen state; extraction and preset persistence belong to `VoicesViewModel`. |
| `VoiceLabScreen` | Collect voice/preset and preview state plus settings; derive selected recipe/dimensions; request embedding loads and preview invalidation; call common visualization math and save/mix actions. | It contains meaningful presentation-local derived logic, including selection compatibility and model-name hints; it is not a second inference engine. |
| `VoiceLabVisualization` | Render `MorphEmbeddingAnalysis` and latent fingerprint bins. | It is a rendering component over pure analysis results, not an embedding extractor. |

The screens generally use `collectAsState()` and `LaunchedEffect` to translate state changes into view-model refreshes. This creates a unidirectional pattern in practice: flow state down, user events up, with a small amount of screen-local state for transient form choices.

## View-model ownership

- `SettingsViewModel` owns persisted app directory, model directory/name, backend preference, welcome dismissal, local model scans, model download/uninstall state, and compiled-backend discovery. It writes `settings.properties` and also stores the app directory in Java `Preferences`.
- `StudioViewModel` owns `StudioUiState`, model capability refresh, synthesis requests, streaming callback handling, accumulated audio, Java Sound playback, text/audio alignment state, and output-file saving. It has a dedicated single-thread executor for native work and releases its engine on clear.
- `VoicesViewModel` owns `VoicePreset` state, recording, extraction of speaker embeddings and ICL prompts, model-session reuse, Voice Lab arithmetic orchestration, temporary preview synthesis/playback, mixed-preset creation, and TSV/file artifacts. It also has a dedicated native executor and cleans up it and preview/recording resources on clear.

The view models are feature owners, not passive stores. For example, `StudioViewModel.generateAudio` loads the model, reads capabilities, selects the effective named-speaker/instruction/embedding/ICL inputs, invokes synthesis, and updates playback state. `VoicesViewModel.createVoicePreset` can load several compatible talker models and create dimension-specific artifacts before persisting one preset.

## Async and lifecycle rules observed in code

- Long-running work is launched from `viewModelScope`; blocking file, audio, and native operations are moved to `Dispatchers.IO` or a view-model-owned single-thread native dispatcher.
- Native sessions are explicitly released when backend settings change and from view-model cleanup. `VoicesViewModel` additionally invalidates preview requests and stops recording/playback.
- Streaming synthesis sends chunks from `QwenEngine` into `StudioViewModel`, which appends audio/alignment state and starts playback as chunks arrive. CLI fallback cannot provide the same streaming behavior; see [engine/native boundary](/architecture/engine-native-boundary.md).
- `VoiceLabScreen` invalidates previews when the recipe, preview text/language, model, or backend changes, and on disposal. This prevents stale preview audio from being treated as current.

## Architectural inference

The intended separation is “Compose describes and reacts; view models coordinate.” The code does not enforce this mechanically: some screens calculate capability-compatible dimensions and the setup screen references engine constants. New behavior should therefore place workflow, persistence, resource cleanup, and native calls in a view model even when the UI can technically implement them inline.
