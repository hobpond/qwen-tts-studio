---
okf_version: 0.2
type: concept
title: Build and verification workflow
description: Entry point for native builds, Gradle execution, packaging, tests, CI, and troubleshooting.
tags:
  - operations
  - build
  - verification
---

# Build and verification workflow

This concept family describes the repository’s development and release operations. Start with [native CPU and CUDA builds](native-builds.md), then use [Gradle and the JVM toolchain](gradle-jvm-toolchain.md) for Kotlin/Desktop tasks. Distribution work is covered by [packaging](packaging.md), while [scripts and entry points](scripts.md) maps the helpers that compose those steps.

For verification, see [tests and smoke checks](tests-and-verification.md). The release-only automation is documented in [the GitHub workflow](github-release-workflow.md). When a command fails, use [common troubleshooting](troubleshooting.md) and retain the native/runtime context described there.

## Observed facts

- `docs/BUILD.md` presents native compilation as a prerequisite for running or packaging the desktop application.
- The root Gradle build exposes Windows-oriented `nativeBuild`, `nativeBuildCuda`, `packageWindows`, and `packageWindowsCuda` tasks through PowerShell scripts.
- The only checked-in workflow is a Windows release workflow; it packages CUDA variants and publishes a GitHub release.
- The checked-in Kotlin tests are common-source embedding arithmetic and visualization tests. Native JNI behavior is not represented by those tests.

Sources: [docs/BUILD.md](../../BUILD.md), [root build.gradle.kts](../../../build.gradle.kts), [tests and smoke checks](tests-and-verification.md), and [the release workflow](../../../.github/workflows/windows-release.yml).

## Inference

The safest local loop after a native or JNI change is: rebuild the matching native flavor, run the Gradle test task, then launch the desktop app for a native smoke check. This follows the project’s documented order and the fact that the unit tests do not load the native library; it is an operational recommendation, not a CI-enforced rule.
