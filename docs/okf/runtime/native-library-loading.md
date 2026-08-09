---
type: runtime-concept
title: Native library loading
description: Search order and dependency loading used before Qwen3 JNI calls are allowed.
tags: [runtime, native, jni, dependencies]
---

# Native library loading

## Observed

- [`QwenEngine.resolveNativeRoot`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L191-L222) searches `user.dir`, its parent, development build locations, and every entry in `jna.library.path`. It looks for `qwen3_tts.dll` on Windows or `libqwen3_tts.so` elsewhere.
- [`ensureNativeLoaded`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L224-L321) is guarded by a static lock and `isNativeLoaded` flag. It loads `ggml-base`, optionally preloads Windows system libraries, best-effort preloads CUDA runtime DLLs, loads available `ggml-cpu`/`ggml-cuda`, then loads `ggml` and the main JNI library with absolute `System.load` paths.
- A backend dependency is required: loading continues only when at least one CPU or CUDA backend was loaded. A CUDA load error is tolerated if another backend was already loaded; other dependency failures are captured as `nativeLoadError`.
- [`build-native.ps1`](../../../scripts/build-native.ps1) and [`build-native.sh`](../../../scripts/build-native.sh) copy the main library and GGML dependencies to the repository root by default. The Windows script can also copy CUDA runtime DLLs when found. That root copy is the first runtime candidate because `user.dir` is searched first.

## Inference / limits

- The root-copy convention makes development and packaged launches work without requiring the runtime to understand every CMake build-directory name. It also means a stale root DLL can win over a newer library elsewhere; the code does not compare timestamps or ABI versions.
- The C++ dependency graph and exported symbol implementations cannot be confirmed from this checkout because the submodule contents are absent. The loading sequence above is directly observed in Kotlin; its success depends on the native artifacts produced by the submodule build.

The repository-root DLL handoff is therefore an invalidation boundary: root artifacts can outlive the CMake build that produced them, and the loader does not compare timestamps, backend flavor, or ABI identity. A stale root library can win over a newer library in another development location.

## Related concepts

- [Native runtime overview](native-runtime.md)
- [JNI engine lifecycle](jni-engine-lifecycle.md)
- [CLI fallback](cli-fallback.md)
- [Runtime failure modes](runtime-failure-modes.md)
