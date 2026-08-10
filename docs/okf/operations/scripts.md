---
okf_version: 0.2
type: concept
title: Development scripts and entry points
description: Shell and PowerShell helpers for building, running, and packaging the application.
tags:
  - operations
  - scripts
  - development
---

# Development scripts and entry points

The scripts provide the intended command-line workflow around Gradle and CMake. They are the operational layer between [native builds](native-builds.md), [Gradle](gradle-jvm-toolchain.md), and [packaging](packaging.md).

## Observed facts

- `scripts/build-native.ps1` is the main Windows native entry point. Its switches include `-Cuda`, `-CudaArchitectures`, `-PortableCpu`, `-UseNinja`, `-Clean`, and `-CopyToRoot`.
- `scripts/build-native.sh` is the Linux native entry point. It reads `CONFIG`, `CUDA`, and `CLEAN` from its positional/environment conventions and copies native outputs to the repository root.
- `scripts/run-compose.ps1` resolves Java 21 or newer, prepends the selected Java and project directories to `PATH`, can call the native build helper with `-BuildNative`, and finally runs `:composeApp:run`. `-Cuda` sets `QWEN_TTS_BACKEND=cuda`; `-DryRun` prints intended work without running it.
- `scripts/run.ps1` is a convenience alias that forwards arguments to `run-compose.ps1`.
- `scripts/package-windows.ps1` owns the multi-step Windows package flow and has retry, clean, CUDA, Ninja, MSI, and CUDA-runtime-bundling switches.
- `scripts/package-linux.sh` owns the three-step Linux native/distributable/copy flow and optionally invokes DEB packaging.
- The root [`build.gradle.kts`](../../../build.gradle.kts) exposes Gradle wrappers for Windows native CPU/CUDA builds and Windows CPU/CUDA packaging. These tasks reject non-Windows operating systems before invoking PowerShell.
- `scripts/debug-native-synth.py` is present as a native synthesis debugging utility, but no checked-in workflow or Gradle task invokes it.

## Inference

For routine Windows development, `run-compose.ps1 -BuildNative` is the most complete one-command path when native code may have changed; for Kotlin-only changes, `:composeApp:run` avoids an unnecessary native rebuild. This follows the script separation and the stale-native-library risk documented in [common troubleshooting](troubleshooting.md).

## Related concepts

- [Native CPU and CUDA builds](native-builds.md)
- [Gradle and the JVM toolchain](gradle-jvm-toolchain.md)
- [Packaging](packaging.md)
