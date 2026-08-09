---
okf_version: 0.2
type: Story
title: Build and verify a local Windows artifact
description: Execute the documented Windows packaging path locally and capture evidence about the produced portable application, native payload, and optional MSI output.
status: done
id: STORY-VOICE-004
parent: EPIC-VOICE-001
milestone: M-VOICE-002
wave: 2
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /docs/BUILD.md
  - /scripts/package-windows.ps1
  - /scripts/build-native.ps1
  - /composeApp/build.gradle.kts
---

# Story: Build and verify a local Windows artifact

## Outcome

Prove, on this Windows checkout, that the application can produce a usable CPU portable artifact from source and establish which packaging stages and native payloads actually completed. MSI creation is a separate acceptance path because it depends on WiX and may be unavailable even when the portable artifact succeeds.

## Tasks

- `TASK-VOICE-005A` (done): preflight the system Python/JDK, Visual Studio/CMake/Ninja, submodule, Gradle wrapper, and required packaging inputs; record blockers with command evidence.
- `TASK-VOICE-005B` (done): run the documented CPU Windows packaging flow and preserve the command, exit status, elapsed time, and output paths.
- `TASK-VOICE-005C` (done): inspect the generated app image and portable ZIP for executable, native DLL, launcher, and stale-payload invariants; assess a non-model smoke check.
- `TASK-VOICE-005D` (done with limitation): record MSI as not attempted because WiX is absent; do not treat portable success as MSI success.
- `TASK-VOICE-005E` (done): update the relevant operations concepts and this Story with observed evidence, cache effects, and remaining risks.

## Acceptance

- The CPU packaging command completes successfully, or the Story is marked `blocked` with the first reproducible failing command and the smallest external prerequisite needed.
- The portable app image and ZIP are located and checked for the expected executable and CPU native DLL set.
- MSI status is explicit: verified, not attempted because a prerequisite is absent, or failed with captured diagnostics.
- The result distinguishes source/build-cache reuse from a newly produced distribution artifact and does not claim model inference or voice generation unless separately exercised.

## Evidence

- CPU packaging completed with exit code `0` in 152 seconds after initializing `external/qwen3-tts-cpp` and its nested `ggml` submodule.
- App image: `composeApp/build/compose/binaries/main/app/qwen-tts-studio`; 245 files, 181,406,403 bytes.
- Portable ZIP: `composeApp/build/compose/binaries/main/portable/qwen-tts-studio-windows-portable.zip`; 105,827,782 bytes, 247 readable entries.
- The app image and ZIP contain matching x64 PE payloads: `qwen-tts-studio.exe`, `qwen3_tts.dll`, `ggml.dll`, `ggml-base.dll`, and `ggml-cpu.dll`; CUDA DLLs are absent as expected for CPU packaging.
- The delegated artifact inspection did not launch the GUI; native DLL loading, JNI symbol compatibility, AVX2 behavior, and model inference remain unverified by that inspection.
- Follow-up user test: the portable artifact launched successfully and reused the existing model download; no model redownload was required.
- MSI was not attempted because WiX tools were absent from PATH and the script-local WiX directories.

## Non-goals

- No CUDA package build unless the CPU path is accepted and CUDA prerequisites are already available.
- No deletion of user caches or generated artifacts beyond the packaging script's documented replacement of its own outputs.
- No changes to native inference logic or the external submodule.
