---
okf_version: 0.2
type: concept
title: Voice data, capabilities, and embedding flows
description: Model-capability-driven behavior, voice preset artifacts, Voice Lab compatibility, and pure embedding analysis.
tags:
  - architecture
  - voice-data
  - capabilities
  - embeddings
  - voice-lab
generated: 2026-08-03
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoiceLabScreen.kt
  - /composeApp/src/commonMain/kotlin/com/qwen/tts/studio/embedding/EmbeddingArithmetic.kt
  - /composeApp/src/commonMain/kotlin/com/qwen/tts/studio/embedding/EmbeddingVisualization.kt
  - /composeApp/src/commonTest/kotlin/com/qwen/tts/studio/embedding/EmbeddingArithmeticTest.kt
  - /composeApp/src/commonTest/kotlin/com/qwen/tts/studio/embedding/EmbeddingVisualizationTest.kt
  - /docs/OKF.md
---

# Voice data, capabilities, and embedding flows

This concept connects the model capability boundary to Studio, Voices, and Voice Lab. The surrounding UI/state ownership is in [Compose screens and view-model state](/architecture/ui-composition-and-state.md); the engine operations are in [engine/native boundary](/architecture/engine-native-boundary.md).

## Capability-driven controls

`QwenEngine.NativeCapabilities` exposes `supportsCloning`, `supportsNamedSpeakers`, `supportsInstruction`, `speakerEmbeddingDim`, `modelKind`, and `speakerCount`. `StudioViewModel` loads capabilities before generation, refreshes available speakers only when named speakers are supported, and suppresses instruction/clone inputs when the capability does not permit them. `VoicesViewModel` similarly refuses Voice Lab preview unless the loaded model reports cloning support.

The model-kind constants currently distinguish unknown, Base, CustomVoice, and VoiceDesign. The stable rule is capability-driven behavior; model names are only a fallback signal. The code does contain filename inference in two places: CLI fallback capability inference in `QwenEngine`, and model/dimension ordering in `VoicesViewModel`. These are compatibility fallbacks, not proof that a filename describes the loaded checkpoint.

## Preset and artifact model

`VoicePreset` stores:

- an ID and display name;
- an optional reference WAV;
- an optional reference transcript;
- speaker-embedding paths keyed by dimension;
- ICL-prompt paths keyed by dimension.

The default system voice has none of the derived artifacts. Custom presets are persisted in `voice-presets.tsv` under the configured app directory. Derived files are stored in sibling managed directories: `embeddings/`, `icl-prompts/`, and `recordings/`; Voice Lab preview embeddings use a temporary `preview/` location. `SettingsViewModel` owns the app directory and model directory, while `VoicesViewModel` owns the voice artifact layout and serialization.

Speaker embeddings and ICL prompts are distinct data products. An embedding is a reusable vector; an ICL prompt is reference-derived data that also depends on transcript text and uses a separate encoder/synthesis path. A preset may have either or both. `VoicesViewModel.createVoicePreset` can extract one or more embedding dimensions and optionally ICL prompts, then records the resulting paths in the preset.

Studio's accepted Read Aloud snapshot can be promoted into this durable preset lifecycle. `StudioScreen` collects a display name, `VoicesViewModel.createVoicePreset` copies the captured reference WAV into managed recordings before extraction, persists the derived paths, and returns the new preset to the screen for selection. This preserves a reusable speaker artifact across restarts; it does not create a native VoiceDesign handle or guarantee identical prosody.

## Compatibility and Voice Lab flow

The observed Voice Lab sequence is:

```text
custom presets -> shared embedding dimensions -> load vectors
               -> pure weighted mean / morph analysis
               -> optional temporary embedding -> Base-model preview
               -> optional persisted mixed preset
```

`VoicesViewModel.loadVoiceLabEmbeddings` accepts only D1024 or D2048 and checks that each file contains exactly the requested number of values. Mixed-preset creation intersects the selected presets' available dimensions, requires at least one shared supported dimension, computes a weighted mean, writes one JSON file per supported dimension, and persists a preset referencing those files.

Voice Lab preview reloads the talker engine, reads native capabilities, builds the selected vector, writes a temporary JSON embedding, synthesizes with that embedding, and deletes the temporary file in `finally`. Preview audio/playback state is separate from the persisted preset. The UI also uses model-name hints to preselect a required dimension and warn about known non-Base model names; the view model's native capability check remains the final gate.

The source checkpoint is not stored with a preset. Equal vector dimensions are checked, but they do not establish that two embeddings came from compatible checkpoints. This is an important invariant and a known limitation, not a guarantee of semantic compatibility.

## Pure common embedding layer

`commonMain` contains no JNI or file I/O for this feature. `EmbeddingArithmetic.weightedMean` validates positive finite weights, equal dimensions, finite values, and optionally rescales to a weighted average L2 norm. `EmbeddingVisualization.analyzeMorph` validates equal finite vectors, computes a local two-dimensional projection, path samples, norms, cosine/angle diagnostics, difference bins, and fingerprint bins.

The visualization is explicitly a local geometric summary of complete vectors; its axes are not time, Hertz, pitch, or learned human-readable controls. `VoiceLabScreen` loads the vectors through `VoicesViewModel`, then calls `EmbeddingVisualization` to derive the object rendered by `VoiceLabVisualization`. Common tests cover weighted means, normalization, singular paths, dimension bins, and finite fingerprint summaries.

## Architectural inference

The repository treats voice artifacts as model-dimension-indexed capabilities rather than one universal voice file. That is visible in the `Map<Int, String>` fields and dimension checks. The missing checkpoint identity means the current persistence model is intentionally incomplete for cross-model provenance; future changes should add compatibility metadata as a coordinated artifact/schema change and update [docs/OKF.md](/docs/OKF.md) if that boundary changes.
