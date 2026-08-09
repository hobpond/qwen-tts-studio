---
okf_version: 0.2
type: Story
title: Studio voice construction
description: Trace how Studio selects a model and voice source, validates compatibility, and passes the resulting voice request to native or CLI synthesis.
status: done
id: STORY-VOICE-002
parent: EPIC-VOICE-001
milestone: M-VOICE-001
wave: 1
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/StudioScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
---

# Story: Studio voice construction

## Tasks

- `TASK-VOICE-002A` (done): trace UI and ViewModel selection of named speaker, embedding, ICL, and instruction inputs.
- `TASK-VOICE-002B` (done): trace persistence and compatibility checks for voice presets and embeddings.
- `TASK-VOICE-002C` (done): update workflow/runtime OKF concepts with the actual request precedence and lifecycle.

## Acceptance

The report must describe the effective voice request shape, precedence rules, model/voice compatibility checks, and native/CLI handoff with file-level evidence.
