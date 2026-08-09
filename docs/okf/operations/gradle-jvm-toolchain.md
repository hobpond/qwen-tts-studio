---
okf_version: 0.2
type: concept
title: Gradle and JVM toolchain
description: Gradle wrapper, Kotlin/Compose versions, Java toolchain selection, and desktop tasks.
tags:
  - operations
  - gradle
  - jvm
  - kotlin
---

# Gradle and JVM toolchain

Gradle drives the Kotlin Multiplatform/Compose Desktop module. Native compilation is invoked separately by PowerShell from the root build; see [native CPU and CUDA builds](native-builds.md) and [scripts and entry points](scripts.md).

## Observed facts

- The repository wrapper is Gradle `9.6.1`, declared in [`gradle-wrapper.properties`](../../../gradle/wrapper/gradle-wrapper.properties).
- [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) pins Kotlin `2.4.0`, Compose plugin `1.11.1`, coroutines `1.11.0`, JNA `5.19.1`, and FileKit `0.8.8`.
- [`composeApp/build.gradle.kts`](../../../composeApp/build.gradle.kts) requests `jvmToolchain(25)`, compiles the desktop target with JVM target `25`, configures JavaExec tasks to use a Java 25 launcher, and gives Compose packaging a Java 25 installation path.
- The same module registers `commonTest` with `kotlin("test")`; the desktop main source set includes Compose Desktop, JNA, and coroutines Swing dependencies.
- The Compose native distribution is configured for DMG, MSI, and DEB targets, with package name `qwen-tts-studio`, and package version from `APP_VERSION` or `1.0.0`.
- The wrapper and Gradle toolchain resolver can provision a JDK, but native JNI compilation and `jpackage` require a full JDK installation, not only a Java runtime. The native and packaging scripts explicitly check for JNI headers/JVM libraries or `jpackage.exe`.
- `docs/BUILD.md` and `scripts/run-compose.ps1` describe Java 21+ as a prerequisite, while the Gradle module and release workflow select Java 25. The checked-in configuration is therefore stricter than the general prerequisite wording.

## Inference

When diagnosing a Java failure, check both the selected Gradle toolchain and `JAVA_HOME`. A Java 21 installation may satisfy the general documentation and run-helper check while still being incompatible with the module’s requested Java 25 toolchain or with packaging expectations. This is an interpretation of the configuration mismatch, not a claim that Java 21 is known to fail in every task.

## Cache boundary

Gradle wrapper distributions, provisioned JDKs, dependency caches, and task state live under Gradle user-home locations (typically `%USERPROFILE%\\.gradle`, subject to `GRADLE_USER_HOME`), not under the application data directory. The repository does not define a custom cache key for its root `Exec` wrappers (`nativeBuild`, `nativeBuildCuda`, `packageWindows`, and `packageWindowsCuda`), so their up-to-date behavior should not be assumed from the wrapper task alone. Native CMake state and Compose/package outputs are separate boundaries; see [native builds](native-builds.md) and [desktop packaging](packaging.md).

## Related concepts

- [Build and verification workflow](build-and-verification.md)
- [Packaging](packaging.md)
- [Tests and smoke checks](tests-and-verification.md)
- [Common troubleshooting](troubleshooting.md)
