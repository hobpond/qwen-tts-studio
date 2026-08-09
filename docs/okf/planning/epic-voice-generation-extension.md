---
okf_version: 0.2
type: EPIC
title: Understand and extend voice generation
description: Establish an evidence-backed map of Windows builds, output caching, Studio voice construction, and a safe path to reusable batched generation.
status: in_progress
id: EPIC-VOICE-001
milestone: M-VOICE-001
wave: 1
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /AGENTS.md
  - /skills/agent-orchestration/SKILL.md
---

# EPIC: Understand and extend voice generation

## Outcome

Document how the application builds and caches Windows artifacts, how Studio constructs a voice request, and how a future batch API could reuse loaded model and voice state while emitting recombinable sound chunks.

## Linked Stories

- [Story: Windows build and output caching](story-windows-build-cache.md) (`STORY-VOICE-001`)
- [Story: Studio voice construction](story-studio-voice-construction.md) (`STORY-VOICE-002`)
- [Story: Reusable batched generation](story-reusable-batched-generation.md) (`STORY-VOICE-003`)
- [Story: Build and verify a local Windows artifact](story-windows-artifact-build.md) (`STORY-VOICE-004`)
- [Story: Kick off batch generation from the portable Studio UI](story-batch-portable-ui.md) (`STORY-BATCH-UI-001`)
@@
- [Story: Reuse an accepted Read Aloud voice for batch generation](story-reuse-read-aloud-voice.md) (`STORY-VOICE-REUSE-001`)
- [Story: Name, save, and reload an accepted Read Aloud voice](story-named-read-aloud-voice.md) (`STORY-VOICE-REUSE-002`)
- [Story: Reuse a VoiceDesign preview through a full Base clone prompt](story-voicedesign-to-clone-prompt.md) (`STORY-VOICE-REUSE-003`)

## Success boundary

Wave 1 is accepted when all Stories have evidence-backed OKF updates, unresolved assumptions are explicit, and the reusable buffered batch boundary is implemented and verified without overstating native or streaming guarantees.

Current state: the original four Stories are done and the portable artifact has been user-tested. `STORY-BATCH-UI-001` is done. `STORY-VOICE-REUSE-001` and `STORY-VOICE-REUSE-002` cover the initial temporary and named speaker-artifact paths but do not complete the official VoiceDesign-to-Clone workflow. `STORY-VOICE-REUSE-003` is planned to bridge an accepted VoiceDesign preview into a full Base-model ICL clone prompt for persistent, consistent batch use. MSI creation remains unverified because WiX is unavailable; fresh-machine behavior, AVX2 portability, and cross-machine JNI compatibility remain outside this evidence. CLI fallback and streaming batching remain explicitly unsupported.

## Non-goals

- No change to the external `qwen3-tts-cpp` submodule boundary.
- No claim that CLI fallback or streaming callbacks satisfy the reusable-session contract.
