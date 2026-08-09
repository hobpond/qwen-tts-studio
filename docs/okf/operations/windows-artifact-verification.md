---
okf_version: 0.2
type: concept
title: Local Windows artifact verification
description: Evidence and limits of the locally produced CPU portable Windows artifact.
tags:
  - operations
  - packaging
  - windows
  - verification
status: verified
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
verified:
  - machine:codex
sources:
  - /scripts/package-windows.ps1
  - /docs/BUILD.md
  - /planning/story-windows-artifact-build.md
---

# Local Windows artifact verification

## Verified CPU portable build

On 2026-08-03, the repository's Windows PowerShell packaging flow initialized the checked-in `external/qwen3-tts-cpp` submodule (including nested `ggml`), built the CPU native backend, ran Compose distributable packaging, copied the native payload, and created the portable ZIP. The run completed with exit code `0` in 152 seconds.

Outputs:

- App image: [`composeApp/build/compose/binaries/main/app/qwen-tts-studio`](../../../composeApp/build/compose/binaries/main/app/qwen-tts-studio)
- Portable ZIP: [`qwen-tts-studio-windows-portable.zip`](../../../composeApp/build/compose/binaries/main/portable/qwen-tts-studio-windows-portable.zip)

The app image contained 245 files and was 181,406,403 bytes. The ZIP was 105,827,782 bytes with 247 readable entries. Both contained matching x64 PE files for the CPU contract:

- `qwen-tts-studio.exe`
- `qwen3_tts.dll`
- `ggml.dll`
- `ggml-base.dll`
- `ggml-cpu.dll`

The CPU package contained no `ggml-cuda.dll` or NVIDIA CUDA runtime DLLs. The debug launcher was present and is designed to create `<app-image>\logs\` on launch.

## Limits and follow-up

The initial inspection was structural, but a follow-up user test launched the portable artifact successfully and confirmed it reused the existing model download without downloading the models again. This verifies the practical installed-data reuse path. It does not independently prove AVX2 behavior, JNI symbol compatibility on another machine, or a fresh-machine model download. The portable package is accepted independently of MSI: WiX was absent from PATH and the script-local WiX directories, so no MSI was attempted.

The build reused available Gradle/JDK caches and CMake state where compatible, while the generated app image and ZIP were newly produced. The packaging script removes/replaces only its own stale app-image native DLLs and portable outputs; it does not prove that repository-root native handoff DLLs or CMake caches are fresh without the native build evidence.

## Related concepts

- [Desktop packaging](packaging.md)
- [Native CPU and CUDA builds](native-builds.md)
- [Story: Build and verify a local Windows artifact](../planning/story-windows-artifact-build.md)
