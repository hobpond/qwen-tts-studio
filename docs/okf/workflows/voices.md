---
okf_version: 0.2
type: Workflow
title: Voices workflow
description: Record or choose reference audio, derive reusable voice artifacts, and manage custom presets.
tags: [workflow, voices, recording, cloning]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
status: draft
sources:
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoicesScreen.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
---

# Voices workflow

Voices turns a WAV reference into a persisted custom [voice preset](voice-presets.md). The workflow is available only when the current model reports cloning support; [model-family capability behavior](model-family-capabilities.md) determines whether that is true.

## Observed in code

- The user can browse for a reference `.wav` or record one from the microphone. Recordings are placed under the app directory's `recordings` folder and the last usable recording is inserted into the reference path field.
- The recorder rejects or deletes audio that is shorter than 0.25 seconds, silent, or above the clipping threshold. On Linux it may use PipeWire and may post-process through `ffmpeg` when available.
- Creating a preset requires a name, a reference WAV, a model directory, and cloning support. A reference transcript is optional in the form, but is used for ICL extraction when supplied.
- The view model searches installed talker models and can extract dimension-keyed embeddings from more than one model. It attempts ICL extraction for each extracted dimension when a transcript is present.
- Preset cards show readiness for D1024 and D2048. If a reference WAV remains available, missing embedding or ICL dimensions can be generated later from the card or from Studio.
- The list includes a built-in `Default Voice (Model)` system entry. Custom entries can be deleted; deletion removes managed embedding and ICL files and rewrites the preset store.

## Inference and user contract

The durable result is not just a name: it is a source reference plus zero or more dimension-specific derived artifacts. A preset may therefore be usable with one selected model family and incomplete for another. The UI should keep the missing-artifact path explicit and should not imply that a preset is universally portable.

Reference audio and transcript are user-provided source material. The recorder's signal checks improve the chance of a usable extraction, but they do not establish speaker consent, ownership, or model compatibility; those remain [privacy and compatibility invariants](compatibility-privacy.md).

## Related concepts

- [Voice preset lifecycle](voice-presets.md)
- [Speaker embeddings](embeddings.md)
- [ICL prompts](icl-prompts.md)
- [Studio workflow](studio.md)
- [Compatibility and privacy invariants](compatibility-privacy.md)
