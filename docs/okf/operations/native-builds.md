---
okf_version: 0.2
type: concept
title: Native CPU and CUDA builds
description: CMake/JNI build paths, native artifacts, CPU portability, and CUDA configuration.
tags:
  - operations
  - native
  - cmake
  - cuda
---

# Native CPU and CUDA builds

The native boundary is the `external/` CMake project, which adds the `external/qwen3-tts-cpp` submodule and builds the `qwen3_tts_shared` JNI wrapper. See [the CMake wrapper](../../../external/CMakeLists.txt) and the broader [build and verification workflow](build-and-verification.md).

## Observed facts

- Windows uses [`scripts/build-native.ps1`](../../../scripts/build-native.ps1). It resolves a full JDK with JNI headers and libraries, loads the Visual Studio C++ environment when needed, chooses Ninja when available or requested, configures `external/`, and builds `qwen3_tts_shared`.
- The default Windows path disables CUDA with `QWEN3_TTS_CUDA=OFF` and `GGML_CUDA=OFF`. The `-Cuda` path enables both and sets `CMAKE_CUDA_ARCHITECTURES`; the default architecture is `native` unless `QWEN_TTS_CUDA_ARCHITECTURES` or the script argument supplies another value.
- `-PortableCpu` disables GGML native CPU detection, enables an AVX2 baseline, and disables AVX-512-related options. The packaging script always passes this option for Windows distributions.
- Windows expects `qwen3_tts.dll`, `ggml.dll`, `ggml-base.dll`, and `ggml-cpu.dll`; CUDA additionally requires `ggml-cuda.dll`. The build script copies these artifacts to the repository root by default for development/runtime discovery.
- Linux uses [`scripts/build-native.sh`](../../../scripts/build-native.sh). `CUDA=ON` enables CUDA, the script builds with `nproc`, copies `libqwen3_tts.so` and GGML libraries to the repository root, and also copies a `qwen3-tts-cli` executable when found.
- Both native paths depend on the `external/qwen3-tts-cpp` submodule. The human build guide gives `git submodule update --init --recursive` as the recovery command when that directory is empty.
- `build_cuda.ps1` is a separate older-style Windows CUDA helper. It uses `external/build`, Visual Studio x64 CMake generation, and copies a smaller set of DLLs, so it is not equivalent to the parameterized `scripts/build-native.ps1` path.

## Inference

The root-copied binaries are a development handoff between native compilation and the JVM/package scripts, rather than repository source artifacts. A clean native build should therefore be treated as a prerequisite whenever JNI signatures, the submodule, CMake linkage, or native structs change. This is inferred from the copy-to-root behavior and the documented stale-DLL failure mode.

## Windows output and cache boundaries

- `scripts/build-native.ps1` uses flavor- and generator-specific CMake directories under `external/`: CPU names follow `build-{ninja|msvc}[-portable]`; CUDA names follow `build-cuda-{ninja|msvc}[-portable]`.
- Those directories contain CMake configuration and incremental state. `-Clean` removes only the selected directory; omitting it allows CMake to reuse its state when configuration remains compatible.
- The DLLs copied to the repository root are generated runtime handoff artifacts, not source and not application voice/model cache. A normal build does not remove unrelated root DLLs, so switching CPU/CUDA or changing JNI ABI can leave stale candidates.
- The root handoff is searched before other development locations by native loading. There is no timestamp or ABI comparison; use a clean, flavor-matched native build when changing the submodule, JNI signatures, or backend.

## Related concepts

- [Gradle and the JVM toolchain](gradle-jvm-toolchain.md)
- [Packaging](packaging.md)
- [Scripts and entry points](scripts.md)
- [Common troubleshooting](troubleshooting.md)
