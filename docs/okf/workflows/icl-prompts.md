---
okf_version: 0.2
type: DataConcept
title: ICL prompts
description: Dimension-keyed reusable reference information extracted from a WAV and transcript for full prompt-based cloning.
tags: [icl, prompts, voices, cloning]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
status: draft
sources:
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/StudioScreen.kt
---

# ICL prompts

An ICL prompt is a reusable derived artifact produced from reference audio plus its transcript. It is distinct from a standalone [speaker embedding](embeddings.md), although both can belong to the same [voice preset](voice-presets.md).

## Observed in code

- The Voices form labels the transcript field "Reference Transcript for ICL Prompt (optional)." Extraction requires a non-blank reference WAV and non-blank reference text.
- ICL extraction loads a separate native ICL prompt encoder session and writes a dimension-keyed JSON path under `appDir/icl-prompts`.
- A newly created source preset can contain embeddings, ICL prompts, or both. If extraction fails, the preset can still be created with a warning when another derived artifact succeeded.
- Missing ICL dimensions can be generated later only when the preset retains a usable reference WAV and reference transcript, and an installed model with the target dimension is available.
- In Studio, ICL mode is available only for a non-system clone preset, and generation passes the ICL path only when cloning is supported and no named speaker is being used. The native engine chooses its ICL synthesis entry point when an ICL path is present.
- CLI fallback has extraction routes, but streaming remains unavailable; native JNI ICL extraction also reports a rebuild-specific error when the loaded library lacks the symbol.

## Inference and user contract

The transcript is part of the derivation input, not merely a display label. A preset with an embedding but no transcript is still usable in embedding mode, while its ICL mode should remain unavailable until the transcript-backed artifact exists. An ICL prompt should not be presented as interchangeable with an embedding; [Studio precedence](studio.md) makes it a separate clone path.

## Related concepts

- [Voices workflow](voices.md)
- [Voice preset lifecycle](voice-presets.md)
- [Speaker embeddings](embeddings.md)
- [Studio workflow](studio.md)
- [Compatibility and privacy invariants](compatibility-privacy.md)
