---
okf_version: 0.2
type: DataConcept
title: Speaker embeddings
description: Dimension-keyed learned speaker vectors used for cloning, Voice Lab arithmetic, and derived presets.
tags: [embeddings, voices, cloning, voice-lab]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
status: draft
sources:
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
  - composeApp/src/commonMain/kotlin/com/qwen/tts/studio/embedding/EmbeddingArithmetic.kt
  - composeApp/src/commonMain/kotlin/com/qwen/tts/studio/embedding/EmbeddingVisualization.kt
---

# Speaker embeddings

A speaker embedding is a learned vector extracted from reference audio. It is a reusable input for the embedding clone mode in [Studio](studio.md) and the arithmetic path in [Voice Lab](voice-lab.md).

## Observed in code

- The project currently recognizes D1024 and D2048 for Voice Lab and for the visible readiness controls. Model capability or fallback filename logic supplies the expected dimension.
- Extracted embeddings are saved as JSON paths under the app directory's `embeddings` folder. The loader also accepts binary float files and validates that a loaded vector has exactly the expected number of values.
- Voice Lab uses positive weighted means. Optional norm matching rescales the result to the weighted average source L2 norm. Non-finite values, empty vectors, unequal dimensions, and invalid weights are rejected.
- The visualization analyzes complete vectors and presents a local 2D projection, differences, and fingerprint summaries. The common embedding code explicitly avoids assigning human-readable meanings to individual coordinates.
- Studio selects the embedding path only when cloning is supported, no named speaker is active, and the current clone mode is `SpeakerEmbedding`.

## Inference and user contract

Dimension is a shape check, not a semantic label. Equal length is necessary for arithmetic and native input acceptance, but it does not prove that two vectors came from the same checkpoint or are perceptually compatible. Treat coordinate visualizations as diagnostics and compare synthesized audio by ear.

Derived embeddings from [Voice Lab](voice-lab.md) should retain the dimension key and remain associated with the model-family compatibility caveat in [voice presets](voice-presets.md).

## Related concepts

- [Voices workflow](voices.md)
- [Voice preset lifecycle](voice-presets.md)
- [ICL prompts](icl-prompts.md)
- [Compatibility and privacy invariants](compatibility-privacy.md)
