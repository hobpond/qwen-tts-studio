---
type: Reference
title: Qwen-TTS Studio OKF bundle
description: Entry point and maintenance guidance for the repository's Open Knowledge Format bundle.
tags: [okf, agents, documentation]
generated:
  by: human:project-maintainer
  at: 2026-08-03T00:00:00Z
status: stable
sources:
  - id: okf-spec
    resource: https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md
    title: Google Cloud Open Knowledge Format specification
---

# Qwen-TTS Studio OKF bundle

The project knowledge corpus is the [`docs/okf/`](okf/) directory. Start at [`docs/okf/index.md`](okf/index.md), then follow the links into architecture, runtime, workflows, and operations concepts.

This file is a human-facing pointer retained for discoverability. The actual OKF bundle uses one concept per Markdown file, YAML frontmatter, reserved `index.md`/`log.md` files, and standard Markdown cross-links.

## Maintaining the bundle

Keep claims grounded in repository files. Add `sources` entries for external references, use `generated` and `verified` when provenance or review is known, and mark obsolete concepts with `status: deprecated` rather than silently breaking links. Update [`docs/okf/log.md`](okf/log.md) when the corpus changes materially.

The bundle follows Google Cloud OKF v0.2. Its only universally required concept frontmatter field is a non-empty `type`; the project convention additionally uses `title`, `description`, `tags`, `generated`, `sources`, and lifecycle fields where useful.

The project-local authoring procedure is [`skills/okf/SKILL.md`](../skills/okf/SKILL.md). It adapts Google’s [reference agent implementation](https://github.com/GoogleCloudPlatform/knowledge-catalog/tree/main/okf), including parser-like validation, progressive indexes, provenance/trust/lifecycle handling, and protection against destructive enrichment edits.

<!--

The historical agent orientation that used to live in this file has been split
into linked OKF concepts so agents can progressively disclose only the area
needed for a task.

-->

OKF means the project’s operational knowledge file for agents. It is a compact, code-grounded map of how the repository works, where behavior lives, and which invariants must survive changes. Update it when the architecture changes.

## 1. What this project is

Qwen-TTS Studio is a local-first Windows/Linux desktop application. The UI is Kotlin Multiplatform/Compose, but the shipped inference engine is native C++ from the `external/qwen3-tts-cpp` submodule. Kotlin talks to that engine through JNI. A command-line executable from the same native backend is used as a fallback for several operations when the JNI library cannot be loaded.

The main user workflow is:

```text
Setup model directory/file
        ↓
Load native engine and model capabilities
        ↓
Choose Studio / Voices / Voice Lab workflow
        ↓
Generate, preview, extract, combine, or save audio/voice artifacts
```

Inference is intended to stay on the user’s machine. Model files and generated voice artifacts are local files; the setup downloader fetches models from Hugging Face when the user asks it to.

## 2. Repository map

| Area | Responsibility |
| --- | --- |
| `composeApp/src/desktopMain/.../App.kt` | Desktop application shell, navigation, shared view models, and screen composition |
| `.../screens/SetupScreen.kt` | Model directory/file selection, model download, backend setup UI |
| `.../screens/StudioScreen.kt` | Text-to-speech form, capability-dependent controls, generation and playback UI |
| `.../screens/VoicesScreen.kt` | Voice preset management and reference-audio operations UI |
| `.../screens/VoiceLabScreen.kt` | Voice morph/average workflow and preview UI |
| `.../screens/VoiceLabVisualization.kt` | Voice Lab preview and latent-fingerprint visualization |
| `.../viewmodel/SettingsViewModel.kt` | Model/settings persistence, model discovery/download state, backend preference |
| `.../viewmodel/StudioViewModel.kt` | Studio model loading, generation requests, streaming/playback state |
| `.../viewmodel/VoicesViewModel.kt` | Voice presets, embedding/ICL extraction, Voice Lab arithmetic and preview playback |
| `.../engine/QwenEngine.kt` | JNI loading, native lifecycle, capability queries, synthesis, extraction, and CLI fallback |
| `composeApp/src/commonMain/.../embedding/` | Pure embedding arithmetic and visualization summaries; suitable for unit tests |
| `external/CMakeLists.txt` | JNI shared-library wrapper around the native submodule |
| `external/qwen3-tts-cpp/` | Git submodule containing inference, GGML, C/C++ APIs, and CLI |
| `scripts/build-native.*` | Native CPU/CUDA build and artifact copying |
| `scripts/run-compose.ps1` | Windows run helper; can rebuild native code first |
| `scripts/package-*.ps1/sh` | Distribution packaging and native-library bundling |
| `docs/BUILD.md` | Human-oriented build/package instructions |
| `docs/DEVELOPMENT_PLAN.md` | Roadmap and known product gaps |

## 3. Runtime architecture

`App.kt` creates the shared `SettingsViewModel`, `VoicesViewModel`, and `StudioViewModel`, then routes the active tab to a screen. The screen owns presentation and event wiring; the view model owns asynchronous work and state flows.

`QwenEngine` is the runtime boundary:

1. Resolve the native library and dependencies from the working directory, development build locations, or `jna.library.path`.
2. Load GGML dependencies, optionally preload CUDA runtime DLLs, then load `qwen3_tts.dll` on Windows or `libqwen3_tts.so` on Linux.
3. Set CPU/CUDA/Auto preference and create a native engine pointer.
4. Load the selected model and query capabilities.
5. Synthesize through JNI, optionally as streaming chunks with text alignment metadata.
6. Release the native pointer when the view model/app is disposed.

If JNI loading or a newer JNI symbol fails, `QwenEngine` may use the native CLI if it can find it. CLI fallback is intentionally less capable: streaming is unavailable, and capabilities may be inferred from the model name rather than read from native metadata.

## 4. Model capabilities and UI behavior

The app should use `NativeCapabilities`, not filename guesses, whenever native metadata is available.

| Capability/model family | Expected behavior |
| --- | --- |
| Base | Custom voice cloning through a reference WAV or speaker embedding; Voice Lab preview is supported |
| CustomVoice | Named speakers; instruction/style control when exposed by the loaded model, especially current 1.7B models |
| VoiceDesign | Instruction-driven voice design when supported; do not assume named speakers |
| Unknown | Keep controls conservative and show actionable setup/runtime errors |

The current capability fields are `supportsCloning`, `supportsNamedSpeakers`, `supportsInstruction`, `speakerEmbeddingDim`, `modelKind`, and `speakerCount`. A model may evolve to expose hybrid capabilities, so UI precedence must remain explicit rather than hard-coded to only Base versus CustomVoice.

## 5. Voice data and compatibility invariants

- A speaker embedding is a learned vector, currently commonly 1024 or 2048 floats; it is not a waveform or frequency plot.
- Voice Lab morph/average operations require compatible vector dimensions.
- The app currently checks dimensions but does not persist or verify the source checkpoint. Do not imply that equal dimensions prove semantic compatibility.
- ICL prompts contain reusable reference information derived from a WAV plus transcript. They are distinct from speaker embedding files.
- Presets may have an embedding, an ICL prompt, or both; missing derived data can be generated later when the selected model supports it.
- Temporary Voice Lab preview embeddings are deleted after preview generation.
- Voice recordings may contain identity-like biometric information. Preserve existing consent/rights warnings when changing UX.

## 6. Build and test workflow

The safe default Windows loop is:

```powershell
pwsh -ExecutionPolicy Bypass -File .\scripts\build-native.ps1
.\gradlew.bat :composeApp:test
.\gradlew.bat :composeApp:run
```

Use `.scriptsun-compose.ps1 -BuildNative` when the native submodule or JNI-facing code changed. Use `-Cuda` for CUDA builds. Native rebuilds are mandatory after changing:

- `external/qwen3-tts-cpp`;
- `external/CMakeLists.txt`;
- JNI declarations/implementations;
- native C/C++ structs passed across the boundary;
- Kotlin `external fun` signatures or corresponding native symbols.

The Gradle module currently requests a JVM toolchain of 25 in `composeApp/build.gradle.kts`; the general docs describe Java 21+ as the prerequisite. When the two disagree, verify the installed toolchain and Gradle configuration before diagnosing a build failure.

## 7. Common failure modes

| Symptom | Likely cause / first check |
| --- | --- |
| `UnsatisfiedLinkError` for a new method | Native DLL is stale; rebuild native code and restart the JVM |
| Missing `ggml`/backend library | Native artifacts were not copied or package omitted a dependency; inspect the resolved native root |
| CUDA load failure | CUDA backend/runtime DLLs are unavailable; try CPU/Auto or use the bundled runtime package |
| No models in Setup | Wrong directory, missing tokenizer/talker GGUF files, or incomplete model download |
| Voice Lab rejects a preset | Embedding dimensions differ, model is not a compatible Base model, or source checkpoint compatibility is unknown |
| Named speakers absent | Loaded model does not report named-speaker capability, or CLI fallback is being used |
| Playback error | Java Sound mixer/device issue; generation may still have succeeded and audio can be saved |

When reporting an error, retain the operation context: selected model name, backend preference, whether JNI or CLI fallback was active, and the exact native error if available.

## 8. Change guidance for agents

Before editing, identify which boundary the change belongs to:

- UI-only: screen and presentation state; avoid moving inference logic into composables.
- Workflow/state: view model; preserve coroutine cancellation and resource cleanup.
- Engine/API: `QwenEngine` plus matching native declarations/implementation; rebuild and smoke test.
- Pure math: common embedding package; add/update unit tests.
- Distribution: scripts, Gradle, CMake, or workflow; verify both artifact names and runtime dependencies.

Do not commit generated native binaries, downloaded model files, temporary `.tts-cli` output, or packaged distributions unless the repository explicitly begins tracking them. Keep human-facing build instructions in `docs/BUILD.md`; keep agent-facing architecture and invariants here.

## 9. Current gaps worth knowing

The roadmap identifies developer-oriented onboarding, incomplete runtime/model validation UX, limited Linux validation, and future hybrid-capability model support as active areas. Treat these as known product gaps, not evidence that the current architecture is unused or broken.
