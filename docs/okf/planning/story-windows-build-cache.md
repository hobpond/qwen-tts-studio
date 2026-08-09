---
okf_version: 0.2
type: Story
title: Windows build and output caching
description: Map Windows native, Gradle, packaging, and generated-output paths, including what is cached and when it is invalidated.
status: done
id: STORY-VOICE-001
parent: EPIC-VOICE-001
milestone: M-VOICE-001
wave: 1
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /docs/BUILD.md
  - /scripts/build-native.ps1
  - /scripts/package-windows.ps1
---

# Story: Windows build and output caching

## Tasks

- `TASK-VOICE-001A` (done): trace Windows native CPU/CUDA build commands, toolchain assumptions, and output locations.
- `TASK-VOICE-001B` (done): trace Gradle/package composition and identify reusable versus invalidated build outputs.
- `TASK-VOICE-001C` (done): update operations OKF concepts with observed facts, commands, and risks.

## Acceptance

The report must name the scripts and output directories, distinguish Gradle/build caches from application runtime caches, and identify any cache key or invalidation behavior that is inferred rather than explicit.
