---
type: runtime-concept
title: Capability detection
description: Native model metadata and conservative filename inference used to control voice, speaker, and instruction workflows.
tags: [runtime, capabilities, models, ui]
---

# Capability detection

## Observed

- The native capability record contains `loaded`, cloning support, named-speaker support, instruction support, embedding dimension, model kind, and speaker count ([definition](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L108-L127)). JNI mode reads it from `nativeGetModelCapabilities`; named-speaker UI data is separately read from `nativeGetAvailableSpeakers` ([queries](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L745-L782)).
- Studio applies the returned flags before generation. It clears the selected speaker unless named speakers are supported, clears instructions unless instruction support is reported, and only passes embedding/ICL inputs when cloning is supported ([`applyCapabilitiesAndSpeakers`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt#L191-L216), [generation selection](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt#L283-L302)).
- In CLI fallback, [`inferCapabilitiesFromModelName`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L984-L1006) classifies names containing `customvoice`, `voicedesign`, or `base`; it infers 2048 dimensions for `1.7b` and 1024 otherwise, reports zero speakers, and uses fixed capability rules.
- Capability refresh is triggered when Studio model settings or backend preference changes ([`StudioScreen.kt`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/StudioScreen.kt#L90-L114)).

## Inference / limits

- Native metadata is the stronger source of truth; filename inference is an explicit fallback approximation. It can be wrong for renamed, hybrid, or future model families, and it cannot discover speaker names.
- A null capability record is handled conservatively in Studio with cloning defaulting to true but named speakers and instructions disabled. That default is observed code behavior, not a validated model guarantee.
- Equal embedding dimensions are treated as the compatibility gate elsewhere in the app; the source checkpoint is not persisted or verified. Dimension equality should not be read as semantic model compatibility.

## Related concepts

- [Model loading](model-loading.md)
- [CLI fallback](cli-fallback.md)
- [Synthesis, streaming, and playback](synthesis-streaming-playback.md)
- [Runtime failure modes](runtime-failure-modes.md)
