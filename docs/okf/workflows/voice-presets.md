---
okf_version: 0.2
type: DataConcept
title: Voice preset lifecycle
description: Represent built-in, source-backed, and derived custom voices and their persisted artifact references.
tags: [voices, presets, persistence, artifacts]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
status: draft
sources:
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoicesScreen.kt
---

# Voice preset lifecycle

`VoicePreset` is the durable identity used by [Voices](voices.md), [Studio](studio.md), and [Voice Lab](voice-lab.md). It is a metadata record pointing to local source and derived artifacts.

## Observed in code

The record contains:

- an id and unique display name;
- an optional `referenceWav` path;
- optional `referenceText` for ICL extraction;
- `speakerEmbeddings`, a map from dimension to file path;
- `iclPrompts`, a map from dimension to file path.

The built-in `Default Voice (Model)` is treated as a system voice when it has no reference WAV and no derived maps. User-created source presets retain their reference WAV and can have one or both derived maps. Voice Lab-generated presets have embeddings but no reference WAV, so they can be blended and synthesized but cannot regenerate missing artifacts from source audio.

Custom presets are serialized in `voice-presets.tsv` under the app directory. Path maps and reference text are encoded for tab-separated storage. Embedding files live under `embeddings`; ICL files live under `icl-prompts`; recordings live under `recordings`. Deleting a custom preset removes only derived files whose canonical parent is the managed embeddings or ICL directory, then removes the TSV entry.

An accepted instruction-driven Read Aloud/Streaming preview can be promoted into this lifecycle from Studio. The save flow requires a name, copies the captured WAV into managed recordings before extraction, persists the derived embedding and preset record, and selects the new preset. The temporary capture files are then released. The current preset schema does not persist checkpoint identity, so compatibility remains dimension- and runtime-checked rather than a cross-checkpoint guarantee.

When the accepted preview came from VoiceDesign, Studio uses the preview text as the clone reference transcript—not the style instruction—and asks the existing preset workflow to create a full ICL prompt with an installed matching Base talker. The selected model is then changed to that Base talker and the voice mode to ICL before batch use. A VoiceDesign or CustomVoice model alone is not treated as the clone-prompt execution model.

## Inference and user contract

A preset is a capability-qualified bundle, not a single model-independent voice object. Its dimension keys communicate which loaded model shapes have been derived, while the absence of a source checkpoint field means portability must remain qualified; see [compatibility and privacy invariants](compatibility-privacy.md).

Unique naming is presentation-level identity: duplicate requested names receive a numeric suffix. It should not be treated as provenance or a stable checkpoint identifier.

Dimension equality is the current compatibility gate for reusing an embedding or ICL artifact. The source checkpoint is not persisted or compared, so equal dimensions do not prove checkpoint compatibility. A source-backed preset can regenerate a missing dimension-specific artifact; a Voice Lab-derived preset cannot do so without reference audio.

## Related concepts

- [Voices workflow](voices.md)
- [Speaker embeddings](embeddings.md)
- [ICL prompts](icl-prompts.md)
- [Studio workflow](studio.md)
- [Compatibility and privacy invariants](compatibility-privacy.md)
