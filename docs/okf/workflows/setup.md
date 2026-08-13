---
okf_version: 0.2
type: Workflow
title: Setup workflow
description: Configure local app data, model files, and the native execution backend before synthesis.
tags: [workflow, setup, models, backend]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
status: draft
sources:
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/SetupScreen.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/SettingsViewModel.kt
---

# Setup workflow

Setup establishes the local locations and runtime choices used by the other workflows. Continue to [Studio](studio.md) after a model is selected; use [Voices](voices.md) only when the loaded model exposes cloning capability.

## Observed in code

- The welcome screen appears when the persisted welcome flag is not dismissed and no matching `qwen-talker-*.gguf` model is found. It permits skipping, but enables **Continue** only when at least one model file is detected (`SetupScreen.kt`, `SettingsViewModel.kt`).
- The app directory is user-selectable and is described as the location for voice presets, recordings, speaker embeddings, and ICL prompt files. The model directory is a separate user-selectable location.
- Model discovery scans regular files ending in `.gguf`, starting with `qwen-talker-`, while excluding tokenizer and speech files. The UI expects one tokenizer GGUF plus one or more talker GGUF files.
- The downloader offers 0.6B Base, 1.7B Base, 1.7B CustomVoice, 1.7B VoiceDesign, and Qwen3-ASR 0.6B validation options. The ASR option uses the Jaffe2718 conversion consumed by the bundled native `qwen3-asr.cpp` tests, rather than the incompatible CrispASR conversion. Downloads write a source marker beside each file; an existing file is skipped only when that marker matches the requested source, so an older ASR artifact is replaced automatically.
- **Auto**, **CPU**, and **CUDA** backend preferences are persisted. CUDA is disabled in the UI when the native build does not report a CUDA backend.
- Settings are written to `settings.properties`; the app directory is also stored in Java Preferences. Changing the backend causes the shared Studio and Voices engines to be released and reloaded on demand.

## Inference and user contract

The practical setup gate is "a readable model directory plus a selected model file," not merely "the downloader completed." A model can be present but still fail native loading; the load error is therefore part of the next-step contract for [model-family capability behavior](model-family-capabilities.md).

Model download is the explicit network action in the setup flow. Synthesis, extraction, and derived voice operations are designed to run through the local native engine; see [compatibility and privacy invariants](compatibility-privacy.md).

## Related concepts

- [Studio workflow](studio.md)
- [Model-family capability behavior](model-family-capabilities.md)
- [Voice preset lifecycle](voice-presets.md)
