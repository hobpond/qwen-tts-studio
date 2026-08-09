---
okf_version: 0.2
type: Invariant
title: Compatibility and privacy invariants
description: Preserve the checks and user-rights boundaries that keep local voice artifacts meaningful and recoverable.
tags: [invariants, compatibility, privacy, consent]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
status: draft
sources:
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoiceLabScreen.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - README.md
  - docs/OKF.md
---

# Compatibility and privacy invariants

These invariants connect [Setup](setup.md), [Studio](studio.md), [Voices](voices.md), and [Voice Lab](voice-lab.md). They are the safety boundaries for future workflow changes.

## Observed in code

- Voice Lab rejects system voices, requires at least two distinct custom voices, and requires a shared supported dimension. The vector loader then validates exact vector length and finite values.
- Voice Lab preview requires a cloning-capable model and D1024 or D2048 at runtime. The UI also blocks model filenames identified as CustomVoice or VoiceDesign for the Base-only preview path.
- The app currently persists dimension-keyed artifact paths but does not persist or verify the source checkpoint. The repository documentation explicitly says equal dimensions are necessary but insufficient for compatibility.
- Studio chooses artifacts matching the loaded model's reported embedding dimension. Missing dimensions stop generation and offer a repair action when source WAV/transcript data permits it.
- Temporary Voice Lab preview embeddings are deleted after generation. Managed derived files are deleted with a custom preset, but the delete code does not delete the referenced source WAV or recording.
- README and OKF guidance describe inference and generated voice artifacts as local, while Setup's downloader explicitly fetches selected model files from Hugging Face.
- Repository documentation warns that speaker embeddings and recordings can contain identity-like biometric information and that derived voices are not automatically anonymous or free of source-voice rights.

## Inference and user contract

1. Never relax dimension checks or silently mix dimensions to make a control appear available.
2. Do not claim equal dimensions establish same-checkpoint compatibility; preserve the current limitation until provenance is persisted and verified.
3. Treat reference WAVs, transcripts, recordings, embeddings, ICL prompts, and generated voices as user-controlled sensitive artifacts. A workflow may offer local processing, but local storage does not establish consent or usage rights.
4. Keep cleanup scoped to managed derived artifacts. Deleting a preset should not implicitly destroy the user's source recording unless that behavior is explicitly designed and surfaced.
5. Preserve the distinction between native capability metadata and filename fallback. Unknown or failed capability state should produce conservative controls and actionable errors.

## Related concepts

- [Model-family capability behavior](model-family-capabilities.md)
- [Voice preset lifecycle](voice-presets.md)
- [Speaker embeddings](embeddings.md)
- [ICL prompts](icl-prompts.md)
- [Voice Lab workflow](voice-lab.md)

