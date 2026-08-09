---
okf_version: 0.2
type: concept
title: Tests and verification
description: Checked-in unit tests, Gradle test entry points, and the limits of current automated coverage.
tags:
  - operations
  - tests
  - verification
---

# Tests and verification

The current automated tests target pure common Kotlin logic. Native changes require a separate rebuild and desktop smoke check; see [native builds](native-builds.md) and [the build and verification workflow](build-and-verification.md).

## Observed facts

- The checked-in test source set contains [`EmbeddingArithmeticTest.kt`](../../../composeApp/src/commonTest/kotlin/com/qwen/tts/studio/embedding/EmbeddingArithmeticTest.kt) and [`EmbeddingVisualizationTest.kt`](../../../composeApp/src/commonTest/kotlin/com/qwen/tts/studio/embedding/EmbeddingVisualizationTest.kt).
- The tests use `kotlin.test` and cover weighted means, norm preservation, invalid weights, morph paths, normalization singularities, endpoint behavior, difference bins, fingerprint summaries, and finite polarity values.
- [`composeApp/build.gradle.kts`](../../../composeApp/build.gradle.kts) declares `kotlin("test")` for `commonTest`; the repository guidance uses `.\gradlew.bat :composeApp:test` as the test command.
- No desktop-native test source set, JNI test harness, CMake test target, or test step appears in the checked-in GitHub release workflow.
- The project guidance calls for a native rebuild and desktop smoke test after JNI-facing/native changes, and identifies the common embedding package as the focused unit-test location.

## Inference

Passing `:composeApp:test` is evidence for the pure embedding logic only. It does not establish that the native DLL/SO, CUDA backend, model files, JNI symbols, audio playback, or packaged runtime dependencies work. Those claims require the relevant build and a launched application or packaged-app check.

## Related concepts

- [Native CPU and CUDA builds](native-builds.md)
- [Gradle and the JVM toolchain](gradle-jvm-toolchain.md)
- [GitHub release workflow](github-release-workflow.md)
- [Common troubleshooting](troubleshooting.md)

