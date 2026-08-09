---
okf_version: 0.2
type: Story
title: Reusable batched generation
description: Design the smallest safe extension for generating many text chunks with one loaded model and voice, producing recombinable audio files.
status: done
id: STORY-VOICE-003
parent: EPIC-VOICE-001
milestone: M-VOICE-001
wave: 1
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
  - /external/qwen3-tts-cpp
  - /docs/okf/runtime/synthesis-streaming-playback.md
---

# Story: Reusable batched generation

## Tasks

- `TASK-VOICE-003A` (done): identify model/session/voice lifetime and whether current native APIs reload state per request.
- `TASK-VOICE-003B` (done): propose a bounded batch contract, chunk naming/metadata, cancellation, failure recovery, and recombination invariants.
- `TASK-VOICE-003C` (done): update runtime/workflow OKF concepts with a staged implementation recommendation and explicit unknowns.
- `TASK-VOICE-004A` (done): expose sample-rate-preserving buffered synthesis and explicit native reusable-session capability.
- `TASK-VOICE-004B` (done): implement atomic WAV/manifest storage and fail-closed numeric recombination.
- `TASK-VOICE-004C` (done): integrate an additive Studio batch API using one immutable model/voice snapshot.

## Acceptance

The design must preserve one loaded model and voice across a batch, define chunk audio invariants and ordering metadata, explain how to recombine safely, and separate evidence from proposed changes.

Implementation status: the Kotlin boundary is implemented and verified with the desktop test suite. Native submodule behavior remains an explicit runtime limitation: the batch API refuses CLI fallback, uses buffered JNI only, and requires the native result to provide a positive sample rate. Streaming batch output is not claimed.
