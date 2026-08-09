---
okf_version: 0.2
type: Story
title: Kick off batch generation from the portable Studio UI
description: Expose the existing reusable batch engine through the portable Studio screen and add runtime evidence needed to distinguish progress, completion, cancellation, and recombination.
status: done
id: STORY-BATCH-UI-001
parent: EPIC-VOICE-001
milestone: M-BATCH-UI-001
wave: 2
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/StudioScreen.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchGeneration.kt
---

# Story: Kick off batch generation from the portable Studio UI

## Tasks

- `TASK-BATCH-UI-001A` (done): add UTF-8 text-file and output-directory selection to Studio.
- `TASK-BATCH-UI-001B` (done): render batch progress, cancellation, failure, completion, and recombination actions.
- `TASK-BATCH-UI-001C` (done): persist per-chunk source text in the manifest and emit progress instrumentation.
- `TASK-BATCH-UI-001D` (done): run desktop tests and exercise the same flow from a newly packaged portable artifact, including a real two-line inference run from the portable UI.
- `TASK-BATCH-UI-001E` (done): review and harden native teardown, cancellation semantics, recombination state/directory ownership, partial-write failures, and chunk-overwrite protection.

## Acceptance

- A portable user can choose a UTF-8 text file, choose an output directory, and start a batch using the current model/voice snapshot.
- The UI reports actual completed-chunk progress, not only a final result.
- Cancellation is visibly distinct from failure and completion; native synthesis remains explicitly cooperative.
- `manifest.json` records each chunk's source text, output file, status, audio metadata, and checksum.
- A completed batch can be recombined into one WAV through the UI, with failures surfaced rather than silently reported as success.
- Evidence includes Kotlin/desktop tests and a packaged portable smoke test; no claim is made for CLI fallback or streaming batching.

## Current evidence

The updated portable ZIP was produced at `composeApp/build/compose/binaries/main/portable/qwen-tts-studio-windows-portable.zip` after `:composeApp:compileKotlinDesktop` and `:composeApp:desktopTest` passed. The portable UI loaded `D:\qwen-tts-studio\models\qwen-talker-1.7b-customvoice-Q8_0.gguf`, accepted `D:\qwen-tts-studio\batch-smoke-output\batch-input.txt`, and completed two chunks without a model reload. Evidence is at `D:\qwen-tts-studio\batch-smoke-output\manifest.json`, `chunk-000000.wav`, and `chunk-000001.wav`; the manifest records both source lines, `COMPLETE` status, 24 kHz audio, frame counts, and checksums. An artifact-level combined WAV was also verified at `D:\qwen-tts-studio\batch-smoke-output\batch-combined.wav`. The UI deliberately reports recombination as a separate action because generation completion guarantees chunk/manifest production, while recombination performs strict checksum, format, ordering, and sample-rate checks; the recombination implementation is covered by desktop tests.
