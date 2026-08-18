---
okf_version: 0.2
type: concept
title: Common build and runtime troubleshooting
description: Evidence-based first checks for toolchain, native, CUDA, packaging, model, and playback failures.
tags:
  - operations
  - troubleshooting
  - runtime
---

# Common build and runtime troubleshooting

Use the symptom to choose the first boundary to inspect: [JVM/Gradle](gradle-jvm-toolchain.md), [native build](native-builds.md), [packaging](packaging.md), or [tests and smoke checks](tests-and-verification.md). Preserve the exact command, OS, selected backend, and native artifact set when reporting a failure.

## Observed facts and first checks

| Symptom | Code-grounded first check |
| --- | --- |
| JNI headers or `UnsatisfiedLinkError` | Confirm a full JDK is selected, then rebuild with [`scripts/build-native.ps1`](../../../scripts/build-native.ps1). The build script checks `jni.h`, `jni_md.h`, `jawt.lib`, and `jvm.lib`; the project guide attributes new-symbol errors to stale native DLLs. |
| Missing `ggml`/backend library | Inspect the repository root and packaged app for the libraries listed by the native build/package scripts. Re-run the appropriate native build or packaging step. |
| CUDA configure/load failure | Verify `CUDA_PATH`, `CUDAToolkit_ROOT`, `nvcc.exe`, the requested architecture, `ggml-cuda.dll`, and runtime DLL availability. A system-runtime package intentionally expects those DLLs on the target machine. |
| Java version or `jpackage` failure | Compare `JAVA_HOME`, the Gradle Java 25 toolchain, and the packaging script’s requirement for `bin\jpackage.exe`. The general docs say Java 21+, but the module and release workflow use Java 25. |
| Missing native submodule sources | Run `git submodule update --init --recursive`, then rebuild. The CMake wrapper adds the TTS and ASR submodules as source dependencies, so a missing or unpopulated checkout cannot configure the backend. |
| Linux DEB missing native libraries | Prefer the standalone Linux app path while investigating. The Linux packaging script itself notes that side-loaded native libraries are not automatically included in DEB/RPM output. |
| Tests pass but app fails | Treat the result as a pure Kotlin-test result only. Run the native build and desktop smoke test because current tests do not load JNI or model/runtime dependencies. |
| Packaged app fails to load native code | Inspect the app image after the Compose step and confirm the package script copied the expected DLLs/SOs into the runtime directory. On Windows, use the generated `qwen-tts-studio-debug.ps1` launcher to capture an app log. |
| Queued batch reports a manifest conflict | Another writer changed the durable batch while TTS, ASR, or rechunking was in flight. Keep the current manifest and artifacts, reload the Batch workspace, and resume from the durable plan; do not force a stale validation or whole-manifest snapshot back into `manifest.json`. |

## Inference

The most useful diagnostic bundle is the operation context plus the exact native state: OS, Java home/version, model path/name if the app was launched, CPU/CUDA preference, native filenames, whether JNI or CLI fallback was active, and the complete native error. This is recommended because the runtime boundary can fail after compilation and because the package scripts deliberately support multiple CUDA runtime contracts.

## Related concepts

- [Build and verification workflow](build-and-verification.md)
- [Native CPU and CUDA builds](native-builds.md)
- [Gradle and the JVM toolchain](gradle-jvm-toolchain.md)
- [Packaging](packaging.md)
