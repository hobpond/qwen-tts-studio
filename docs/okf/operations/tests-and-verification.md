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

Automated coverage now includes pure common Kotlin logic, desktop batch/state tests, a headless application-boundary batch-verification runner, and a seam-tested ASR concurrency runner. Native changes still require a separate native rebuild and, when native evidence is needed, a native-mode batch verification run or desktop smoke check; see [native builds](native-builds.md), [headless batch verification](agent-smoke-testing.md), [ASR concurrency testing](asr-concurrency-testing.md), and [the build and verification workflow](build-and-verification.md).

## Observed facts

- The checked-in test source set contains [`EmbeddingArithmeticTest.kt`](../../../composeApp/src/commonTest/kotlin/com/qwen/tts/studio/embedding/EmbeddingArithmeticTest.kt) and [`EmbeddingVisualizationTest.kt`](../../../composeApp/src/commonTest/kotlin/com/qwen/tts/studio/embedding/EmbeddingVisualizationTest.kt).
- The tests use `kotlin.test` and cover weighted means, norm preservation, invalid weights, morph paths, normalization singularities, endpoint behavior, difference bins, fingerprint summaries, and finite polarity values.
- [`composeApp/build.gradle.kts`](../../../composeApp/build.gradle.kts) declares `kotlin("test")` for `commonTest` and `desktopTest`; the configured desktop test entry point is `.\gradlew.bat :composeApp:desktopTest`.
- [`AgentSmokeRunnerTest.kt`](../../../composeApp/src/desktopTest/kotlin/com/qwen/tts/studio/agent/AgentSmokeRunnerTest.kt) drives `StudioViewModel` through fake generation, deterministic validation, and WAV recombination, and verifies native-mode missing-model fail-closed behavior.
- [`HeadlessBatchVerificationRunner`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/agent/AgentSmokeRunner.kt) is also available through the normal application entry point with `--headless-batch-verify`; it writes a machine-readable report and exits nonzero when the completion contract is not met. The former `--agent-smoke` spelling is retained as a compatibility alias.
- For an already-complete manifest whose TTS runtime fingerprint must remain unchanged, `--headless-batch-verify --mode native --manifest PATH --resume --validate-manifest-only` drives the same load, validate, and combine callbacks without resuming TTS. It loads one ASR engine, persists ASR model metadata and per-row validation state, and preserves the original TTS provenance.
- [`HeadedBatchUiInstrumentationTest.kt`](../../../composeApp/src/desktopTest/kotlin/com/qwen/tts/studio/screens/HeadedBatchUiInstrumentationTest.kt) drives the rendered Batch surface through Compose semantics, including a 320-chunk lazy-list/search/filter usability path and the compact terminal workflow.
- [`AsrConcurrencyRunnerTest.kt`](../../../composeApp/src/desktopTest/kotlin/com/qwen/tts/studio/agent/AsrConcurrencyRunnerTest.kt) proves synchronized two-engine work, loaded-engine reuse, serial-baseline transcript matching, and fail-closed CUDA gate behavior through a fake engine seam. The native runner is available through `--agent-asr-concurrency` and the profile wrapper described in [ASR concurrency testing](asr-concurrency-testing.md).
- No CMake test target or test step appears in the checked-in GitHub release workflow. Native-mode agent runs and portable UI smoke checks remain local acceptance evidence.
- The project guidance calls for a native rebuild and desktop smoke test after JNI-facing/native changes, and identifies the common embedding package as the focused unit-test location.

## Inference

Passing `:composeApp:desktopTest` establishes the checked-in Kotlin desktop tests and fake application workflow. A fake agent pass does not establish that the native DLL/SO, CUDA backend, model files, JNI symbols, acoustic quality, audio playback, or packaged runtime dependencies work. Those claims require a matching native build plus a passing native-mode agent run, launched application, or packaged-app check.

## Related concepts

- [Native CPU and CUDA builds](native-builds.md)
- [Headless batch verification](agent-smoke-testing.md)
- [ASR concurrency testing](asr-concurrency-testing.md)
- [Gradle and the JVM toolchain](gradle-jvm-toolchain.md)
- [GitHub release workflow](github-release-workflow.md)
- [Common troubleshooting](troubleshooting.md)
