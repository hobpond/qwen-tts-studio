---
okf_version: 0.2
type: Story
title: Reuse a VoiceDesign preview through a full Base clone prompt
description: Complete the Qwen VoiceDesign-to-Clone workflow by turning an accepted Studio preview into a persisted full ICL voice prompt and using it for consistent batch generation.
status: planned
id: STORY-VOICE-REUSE-003
parent: EPIC-VOICE-001
milestone: M-VOICE-REUSE-003
wave: 3
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchGeneration.kt
  - /external/qwen3-tts-cpp/README.md
  - id: qwen-voice-design-then-clone
    resource: https://github.com/QwenLM/Qwen3-TTS/blob/main/README.md#voice-design-then-clone
    title: Qwen3-TTS official Voice Design then Clone workflow
---

# Story: Reuse a VoiceDesign preview through a full Base clone prompt

## User outcome

The user can generate and audition a voice with the VoiceDesign model, accept the preview, name it, and use that same designed voice for long-form batch generation. The application converts the accepted preview into the full reusable Qwen clone prompt, switches to a compatible Base model for cloning, and reuses one prompt across all chunks without redesigning the voice.

## What exists today

The native pipeline already provides the required primitives: speaker-embedding extraction, full ICL prompt extraction, persisted ICL prompt files, ICL prompt loading, and synthesis from a saved prompt. `VoicesViewModel` already uses the ICL extraction path for ordinary reference-audio presets, and `BatchGenerationRequest` already carries an ICL prompt path.

The missing behavior is Studio orchestration. `Keep voice` currently attempts the speaker-embedding path from the current generation session; it does not create the full ICL prompt from the accepted VoiceDesign clip, does not require the preview transcript, and does not transition batch synthesis to a compatible Base model. CustomVoice/VoiceDesign instruction support is therefore not equivalent to reusable clone-prompt support.

## Tasks

- `TASK-VOICE-REUSE-003A` (done): model the two-stage VoiceDesign → Base clone-prompt workflow and compatibility rules, including matching model size and transcript requirements.
- `TASK-VOICE-REUSE-003B` (done): capture the accepted preview WAV and exact preview text/instruction as a durable reference artifact.
- `TASK-VOICE-REUSE-003C` (done): route save through the compatible Base model's existing ICL prompt encoder and persist the full ICL prompt through the named voice-preset lifecycle.
- `TASK-VOICE-REUSE-003D` (review): make batch generation select the compatible Base model plus saved ICL prompt, load once, and reuse the prompt for every chunk with no VoiceDesign instruction resubmission.
- `TASK-VOICE-REUSE-003E` (done): expose the model transition in the save dialog and provide clear errors when no compatible Base model or transcript is available.
- `TASK-VOICE-REUSE-003F` (planned): add focused tests for prompt-path reuse, model transition, instruction suppression, persistence/reload, and mismatch rejection.
- `TASK-VOICE-REUSE-003G` (planned): perform portable real-model acceptance: design preview, accept/name, restart/reload, generate multiple chunks, and verify one ICL prompt/model identity in the manifest.

## Acceptance

- VoiceDesign preview generation remains instruction-driven and can be auditioned repeatedly before acceptance.
- Accepting a preview records the exact preview WAV, preview text, design instruction, source model, and target clone-model requirements.
- The app creates a full ICL clone prompt with `x_vector_only_mode` equivalent to full-prompt mode; speaker-embedding-only fallback is explicit and not presented as the complete workflow.
- The saved voice can be named, loaded after restart, and selected from the existing voice workflow.
- Batch generation uses a compatible Base model and the persisted ICL prompt for every chunk, performs one model load, and does not pass the VoiceDesign instruction to each chunk.
- The manifest records design-model provenance, clone-model identity, prompt mode, prompt path/checksum, preview text, and source instruction.
- Missing transcript, missing compatible Base model, wrong embedding dimension, stale prompt, and model mismatch fail clearly before batch output begins.
- Existing CustomVoice named-speaker/instruction generation, ordinary Base voice cloning, ICL presets, CLI fallback, and streaming playback remain intact outside this opt-in workflow.

Current implementation evidence: VoiceDesign capture retains the preview WAV and exact preview text without requiring the design model to expose a speaker encoder. Saving passes that transcript to the existing `VoicesViewModel.createVoicePreset` ICL extraction path, selects a matching installed Base model, and switches Studio to ICL mode. Kotlin compile and desktop tests pass. Real-model prompt extraction and multi-chunk portable acceptance remain pending.

## Non-goals

- Do not claim that a CustomVoice speaker selection is a reusable VoiceDesign clone prompt.
- Do not add a native first-class VoiceDesign handle unless the submodule exposes one; use the documented reference-audio-to-clone-prompt bridge.
- Do not redesign the native Qwen inference or duplicate its prompt extraction in Kotlin.

## Related concepts

- [Reuse an accepted Read Aloud voice](story-reuse-read-aloud-voice.md)
- [Name, save, and reload an accepted Read Aloud voice](story-named-read-aloud-voice.md)
- [Reusable batched generation](../runtime/batched-generation.md)
- [Voice preset lifecycle](../workflows/voice-presets.md)
