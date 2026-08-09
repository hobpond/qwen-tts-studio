---
okf_version: 0.2
type: CapabilityModel
title: Model-family capability behavior
description: Map native capability fields and fallback family inference to user-visible Studio, Voices, and Voice Lab behavior.
tags: [capabilities, models, base, customvoice, voicedesign]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
status: draft
sources:
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
  - docs/OKF.md
---

# Model-family capability behavior

The runtime exposes capabilities as data. The user-facing workflows should consume those fields rather than expose controls merely because a screen can render them.

## Observed in code

`QwenEngine.NativeCapabilities` reports `supportsCloning`, `supportsNamedSpeakers`, `supportsInstruction`, `speakerEmbeddingDim`, `modelKind`, and `speakerCount`. The native path reads this record after loading. The CLI fallback instead infers a conservative record from the selected model name:

| Family signal | Fallback behavior observed in `QwenEngine` | User-facing consequence |
| --- | --- | --- |
| `base` | cloning enabled; no named speakers or instruction implied | [Voices](voices.md) and Base-style cloning can be offered |
| `customvoice` | cloning and named speakers enabled; instruction enabled only when the name contains `1.7b` | [Studio](studio.md) can show named speakers and, for the supported size, style instruction |
| `voicedesign` / `voice-design` | cloning and instruction enabled; named speakers disabled | Studio can show instruction, while Voice Lab remains Base-only |
| unknown | kind unknown, no named speakers or instruction; dimension inferred as 1024 unless `1.7b` appears | keep controls conservative and surface load/runtime errors |

The fallback dimension heuristic is D2048 for filenames containing `1.7b` and D1024 otherwise. Native metadata is the preferred source when available. `StudioViewModel` clears named-speaker state when the capability is absent and clears instruction text when instruction is unsupported.

## Inference and user contract

The family table is a fallback compatibility model, not a universal statement about future checkpoints. Native metadata can expose hybrid capabilities, so behavior should be keyed by the individual booleans and dimension, with `modelKind` used for explanatory copy and Base-only policy where required.

There is a deliberate two-layer guard for Voice Lab: the screen uses filename signals to explain obvious non-Base selections, while the view model checks runtime cloning capability and dimension before preview. This is defensive UX, not evidence that filename classification is authoritative.

## Related concepts

- [Setup workflow](setup.md)
- [Studio workflow](studio.md)
- [Voices workflow](voices.md)
- [Voice Lab workflow](voice-lab.md)
- [Compatibility and privacy invariants](compatibility-privacy.md)
