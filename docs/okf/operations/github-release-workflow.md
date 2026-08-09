---
okf_version: 0.2
type: concept
title: GitHub Windows release workflow
description: Trigger, environment, packaging, artifact collection, and release publication for Windows builds.
tags:
  - operations
  - github-actions
  - release
  - windows
---

# GitHub Windows release workflow

The repository has one checked-in workflow: [`windows-release.yml`](../../../.github/workflows/windows-release.yml). It is a release pipeline rather than a general build/test matrix. Its package mechanics are detailed in [packaging](packaging.md), and its verification limits are discussed in [tests and smoke checks](tests-and-verification.md).

## Observed facts

- The workflow runs on `windows-latest` for pushes to tags matching `v*` or manual dispatch. Manual dispatch accepts an optional version string and prerelease boolean.
- It checks out recursive submodules, then synchronizes and initializes them again with depth 1.
- It sets up Temurin Java 25, Gradle setup, Ninja, and CUDA. It locates `nvcc.exe`, exports `CUDA_PATH` and `CUDAToolkit_ROOT`, and adds the CUDA `bin` directory to `PATH`.
- It resolves a release version from the manual input, tag, or `0.1.<run number>`, then derives a numeric `APP_VERSION` for MSI packaging.
- The first package command is `scripts/package-windows.ps1 -Cuda -UseNinja -BuildMsi -RequireMsi -Clean`. It collects a “windows-cuda-system” ZIP and MSI.
- The second package command reuses the native outputs with `-SkipNativeBuild` and adds `-BundleCudaRuntime`, then collects “windows-cuda-bundled” ZIP and MSI artifacts.
- It uploads all collected files as a workflow artifact and publishes them with `softprops/action-gh-release@v2`. The release description states the runtime dependency difference between the two CUDA variants.
- No Gradle test invocation, CPU package job, Linux job, pull-request trigger, or post-package application smoke test is present in this workflow.

## Inference

The workflow assumes the first CUDA package succeeded and leaves the native output in the repository root for the bundled-runtime package. That is why the second invocation uses `-SkipNativeBuild`; changing that sequencing would change the release contract. This is inferred from the two commands and their shared working tree.

## Related concepts

- [Packaging](packaging.md)
- [Native CPU and CUDA builds](native-builds.md)
- [Tests and verification](tests-and-verification.md)
- [Common troubleshooting](troubleshooting.md)

