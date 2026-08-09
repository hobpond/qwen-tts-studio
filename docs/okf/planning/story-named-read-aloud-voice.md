---
okf_version: 0.2
type: Story
title: Name, save, and reload an accepted Read Aloud voice
description: Turn an accepted instruction-driven Read Aloud/Streaming preview into a durable named voice preset that can be loaded and reused for later batch generation.
status: review
id: STORY-VOICE-REUSE-002
parent: EPIC-VOICE-001
milestone: M-VOICE-REUSE-002
wave: 3
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/StudioScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/VoicesScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
---

# Story: Name, save, and reload an accepted Read Aloud voice

## User outcome

After generating several instruction-driven Read Aloud/Streaming previews, the user can name the accepted voice and save it. The voice appears in the existing voice-preset workflow and can be loaded after restarting the application, then selected for consistent batch generation without redesigning the voice for every chunk.

## Tasks

- `TASK-VOICE-REUSE-002A` (done): define the persisted artifact contract for a captured voice, including managed reference audio, speaker embedding, source instruction, and lifecycle cleanup; model checkpoint identity remains an explicit limitation.
- `TASK-VOICE-REUSE-002B` (done): add a name-and-save action for the current accepted Read Aloud/Streaming preview and persist it through the existing `VoicePreset` storage path.
- `TASK-VOICE-REUSE-002C` (done): load saved captured voices on startup, expose them in the existing voice selector, and make selection feed the reusable batch path.
- `TASK-VOICE-REUSE-002D` (review): compile and desktop-test the persistence integration; focused real-model persistence tests remain desirable.
- `TASK-VOICE-REUSE-002E` (review): rebuild the portable artifact and identify the configured real-model GUI save/restart/reload check as the remaining manual acceptance evidence.

## Acceptance and evidence

- The user can give the accepted preview a non-empty display name before saving.
- Saving copies or moves the reference WAV and derived embedding out of temporary storage into the app-managed voice-artifact area.
- The persisted preset survives application restart and is visible in the existing Voices workflow and selector.
- Loading a saved preset validates model identity/backend and embedding dimension before it is used; incompatible or missing artifacts fail clearly without silent fallback to a different voice.
- Selecting the saved voice for batch generation uses one fixed speaker artifact for all chunks and does not resubmit the original voice-design instruction for each chunk.
- Source instruction and model/backend/dimension provenance remain inspectable in the preset or batch manifest, while exact generated prosody is not overstated as persisted.
- Duplicate names, invalid names, failed extraction, and deletion/cleanup do not leave orphaned or misleading presets.
- Existing named-speaker, ordinary embedding, ICL, CLI fallback, and streaming playback behavior remains intact outside this opt-in save/load path.

Evidence so far: `:composeApp:compileKotlinDesktop :composeApp:desktopTest` passes; `scripts/package-windows.ps1 -SkipNativeBuild` passes and produces the portable app/ZIP; the packaged application contains the new Studio save flow. Real-model GUI save/restart/reload and multi-chunk acceptance have not been run by the orchestrator and remain required before marking this Story and Wave done.

## Dependencies and non-goals

This story depends on the temporary capture path in [Reuse an accepted Read Aloud voice](story-reuse-read-aloud-voice.md) (`STORY-VOICE-REUSE-001`) and reuses the existing preset infrastructure in `VoicesViewModel`. It does not introduce a native first-class voice handle, persist exact per-utterance prosody, or change the `external/qwen3-tts-cpp` submodule.

## Related concepts

- [Reuse an accepted Read Aloud voice](story-reuse-read-aloud-voice.md)
- [Reusable batched generation](../runtime/batched-generation.md)
- [Voice data and capability flows](../architecture/voice-data-and-capability-flows.md)
- [Voices workflow](../workflows/voices.md)
