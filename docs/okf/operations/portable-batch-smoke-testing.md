---
okf_version: 0.2
type: Operation
title: Portable batch smoke testing
description: Repeatable preparation and evidence rules for exercising batch generation from the packaged Windows UI.
status: verified
tags: [operations, windows, portable, batching, verification]
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /scripts/smoke-batch-portable.ps1
  - /docs/BUILD.md
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/StudioScreen.kt
---

# Portable batch smoke testing

## Preparation

Run the helper from the repository after producing the portable app image:

```powershell
.\scripts\smoke-batch-portable.ps1
```

The helper creates a unique temporary output directory containing `batch-input.txt` with two UTF-8 lines. It validates the packaged executable and reports the model directory, fixture, output directory, and expected files. It exits with code `2` when the model directory contains no `.gguf` file; that is a prerequisite result, not a successful inference run.

Once a model directory is available, use `-Launch`, select the reported text fixture and output directory in Studio, then press **Start batch**. A successful run must produce:

- `manifest.json`
- `chunk-000000.wav`
- `chunk-000001.wav`

Press **Recombine WAV**, select a destination outside the batch directory, and verify the combined WAV exists and can be reopened. The manifest must retain both source lines, complete status, sample rate, frame count, and checksums.

## Evidence levels

- Portable executable startup proves packaging/launch integrity only.
- The fake `BatchEngine` desktop tests prove one load and sequential generation without a model.
- A real portable smoke run proves model loading, voice selection, native buffered reuse, chunk persistence, and the completed UI batch flow. Artifact-level recombination can then be checked from the manifest/chunks; the strict recombination implementation is also covered by desktop tests.

Do not close `STORY-BATCH-UI-001` on the first two evidence levels alone. CLI fallback, streaming batching, fresh-machine download, and MSI behavior remain separate boundaries.
