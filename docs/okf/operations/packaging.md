---
okf_version: 0.2
type: concept
title: Desktop packaging
description: Windows portable/MSI packaging, CUDA runtime variants, and the Linux distributable path.
tags:
  - operations
  - packaging
  - distribution
  - cuda
---

# Desktop packaging

Packaging combines a native build, Compose’s app image, and a post-build native-library copy. See [Gradle and the JVM toolchain](gradle-jvm-toolchain.md) for the distribution configuration and [native CPU and CUDA builds](native-builds.md) for the binaries being bundled.

## Observed facts

- [`scripts/package-windows.ps1`](../../../scripts/package-windows.ps1) selects a full JDK with `jpackage.exe`, optionally rebuilds native code, runs `:composeApp:createDistributable`, copies native DLLs into the app image, creates `qwen-tts-studio-windows-portable.zip`, and can build an MSI with `jpackage`.
- Windows packaging uses `-PortableCpu`, so packaged CPU binaries use the AVX2 baseline and disable AVX-512. The script’s default CUDA architecture list is `75-real;80-real;86-real;89-real;90-real;100-real;110-real;120-real`, overridable with `QWEN_TTS_PACKAGE_CUDA_ARCHITECTURES`.
- `-Cuda` adds `ggml-cuda.dll`. Without `-BundleCudaRuntime`, the package does not include CUDA runtime DLLs and the target machine must provide them through `CUDA_PATH`, `CUDAToolkit_ROOT`, or `PATH`.
- `-BundleCudaRuntime` searches for `cudart64_*.dll`, `cublas64_*.dll`, and `cublasLt64_*.dll`, copies found DLLs into the package, and produces a larger offline-oriented package. The script warns rather than failing if requested runtime DLLs are not found.
- MSI creation needs WiX tools. `-RequireMsi` makes an MSI failure fatal; otherwise the portable package can remain usable after an MSI failure.
- [`scripts/package-linux.sh`](../../../scripts/package-linux.sh) builds native libraries, runs `:composeApp:createDistributable`, and copies `.so` files into the standalone app’s `lib` directory. It can call `:composeApp:packageDeb` when `BUILD_DEB=true`, but its own comment says side-loaded native libraries are not automatically included in DEB/RPM output.
- The Compose module declares DMG, MSI, and DEB target formats, but the checked-in packaging helpers provide an explicit Windows portable/MSI flow and a Linux standalone flow.

## Inference

The Windows “system” and “bundled” CUDA packages are materially different runtime contracts: the former is smaller but environment-dependent, while the latter is intended to work without a separate CUDA Toolkit installation. The names and release descriptions support this distinction; actual driver compatibility still depends on the target machine.

## Windows artifact paths and invalidation

- The Windows helper creates the Compose app image at `composeApp/build/compose/binaries/main/app/qwen-tts-studio`.
- It copies current root DLLs into that image after `:composeApp:createDistributable`, removes stale native DLLs from the image first, and writes diagnostics from its generated debug launcher under `<app-image>\logs\`.
- The portable package is `composeApp/build/compose/binaries/main/portable/qwen-tts-studio-windows-portable.zip`; MSI output is under `composeApp/build/compose/binaries/main/msi/`. Existing ZIP/MSI outputs are deleted before replacement.
- Gradle does not model the post-distributable DLL copy as a task output. The Compose app image is mutated by the packaging script after Gradle completes, and a successful Gradle task alone does not prove the native payload is current.
- The release flow can clean-build CUDA once and reuse the result with `-SkipNativeBuild` for a bundled variant. System CUDA packages omit NVIDIA runtime DLLs; bundled packages copy matching `cudart64_*`, `cublas64_*`, and `cublasLt64_*` files when found, with a warning rather than a hard failure when they are absent.

## Related concepts

- [GitHub release workflow](github-release-workflow.md)
- [Scripts and entry points](scripts.md)
- [Common troubleshooting](troubleshooting.md)
