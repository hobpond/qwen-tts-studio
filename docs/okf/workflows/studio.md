---
okf_version: 0.2
type: Workflow
title: Studio workflow
description: Select a loaded model capability path, synthesize text, and play or save the resulting WAV.
tags: [workflow, studio, synthesis, playback]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
status: draft
sources:
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/StudioScreen.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
---

# Studio workflow

Studio is the primary text-to-speech path after [Setup](setup.md). It is capability-driven: the selected model is loaded, its native capabilities are refreshed, and only supported speaker or instruction inputs are sent to synthesis.

## Observed in code

- The screen selects an available model file and one of ten fixed language names, accepts up to 5,000 text characters, and exposes a streaming checkbox.
- Capability refresh runs when model directory, model filename, or backend preference changes. The UI receives cloning, named-speaker, instruction, model-kind, and speaker-embedding-dimension state from `StudioViewModel`.
- If named speakers are supported, the screen shows the engine-reported speaker list. Otherwise, if cloning is supported, it shows the built-in default voice and persisted custom [voice presets](voice-presets.md).
- For a custom preset, the screen can switch between **Embedding** and **ICL** modes. An ICL option is enabled when a prompt exists or the preset has a saved reference transcript.
- At generation time, named-speaker selection takes precedence. If no named speaker is used, an ICL prompt takes precedence over a speaker embedding. Instructions are passed only when the loaded capability says they are supported.
- Non-streaming generation produces playable audio. Streaming appends native chunks, starts playback as chunks arrive, and retains collected audio. The user can pause, seek, replay, and save available audio to a WAV file.
- CLI fallback can synthesize non-streaming audio, but `QwenEngine.generateStreaming` reports streaming unavailable in fallback mode.

A selected custom voice is resolved to the current model's embedding dimension: named speaker takes precedence, then an ICL prompt path, then a speaker embedding path. Studio passes no reference-WAV path in its normal synthesis request. Changing model directory, model name, or backend reloads the engine and re-resolves dimension-keyed artifacts. The visible selected voice/clone mode can persist across the change even when the new capability no longer uses that artifact.

## Inference and user contract

The visible Studio controls are a projection of the loaded model capability record, not a promise that every model family supports every voice mechanism. A change of model or backend can invalidate the previously selected voice path; missing dimension-specific artifacts are surfaced with generation actions instead of being silently substituted.

The effective input precedence can be summarized as:

    named speaker -> ICL prompt -> speaker embedding -> model default

This precedence is inferred from `StudioViewModel.generateAudio`; it is an implementation rule, not a claim that the underlying model families are semantically interchangeable. See [ICL prompts](icl-prompts.md), [embeddings](embeddings.md), and [model-family capability behavior](model-family-capabilities.md).

## Related concepts

- [Setup workflow](setup.md)
- [Model-family capability behavior](model-family-capabilities.md)
- [Voice preset lifecycle](voice-presets.md)
- [ICL prompts](icl-prompts.md)
- [Compatibility and privacy invariants](compatibility-privacy.md)
