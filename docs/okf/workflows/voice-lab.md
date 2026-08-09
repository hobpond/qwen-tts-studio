---
okf_version: 0.2
type: Workflow
title: Voice Lab workflow
description: Blend compatible speaker embeddings, inspect a local latent summary, preview, and save a derived voice.
tags: [workflow, voice-lab, morph, average]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
status: draft
sources:
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoiceLabScreen.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoiceLabVisualization.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
  - composeApp/src/commonMain/kotlin/com/qwen/tts/studio/embedding/EmbeddingArithmetic.kt
---

# Voice Lab workflow

Voice Lab operates on custom presets that contain speaker [embeddings](embeddings.md). It has two user-facing recipes: two-voice morphing and equal-weight averaging.

## Observed in code

- System voices are excluded. At least two custom voices with embeddings are needed.
- **Morph** selects Voice A and Voice B and moves between them with a 0..1 slider. **Average** selects two or more voices and assigns equal weights.
- The screen computes the intersection of available dimensions and keeps only D1024 and D2048. A selection is not ready when the selected voices have no shared supported dimension.
- Optional norm matching rescales the weighted mean to the weighted average source L2 norm. The UI describes this as a heuristic and does not claim naturalness.
- The latent fingerprint visualization is a local geometric summary of the selected vectors. The common embedding code explicitly says its axes are not time, Hertz, pitch, or learned human-readable labels.
- Preview requires a Base-compatible path. The screen blocks model filenames containing `customvoice` or `voicedesign`, and the view model also requires runtime cloning support plus a reported D1024 or D2048 dimension.
- Preview writes a temporary JSON embedding under `appDir/preview`, synthesizes with the selected Base model, starts playback, and deletes the temporary embedding in `finally`. The result can be replayed or saved as a new generated preset.
- Saving writes a new preset containing generated embeddings and no reference WAV. It does not create an ICL prompt.

## Inference and user contract

Voice Lab is a derived-voice workflow, not a checkpoint conversion workflow. Dimension intersection is a necessary gate enforced by the UI and vector loader, but the repository does not persist source-checkpoint identity. Users should treat the result as experimental and validate it by ear with the same compatible Base family; see [compatibility and privacy invariants](compatibility-privacy.md).

The preview deletion is an operational privacy and cleanup invariant: the temporary recipe embedding is needed for the request, but is not intended to become a durable artifact unless the user explicitly saves the resulting preset.

## Related concepts

- [Speaker embeddings](embeddings.md)
- [Voice preset lifecycle](voice-presets.md)
- [Compatibility and privacy invariants](compatibility-privacy.md)
- [Studio workflow](studio.md)
