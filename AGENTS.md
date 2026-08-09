# Agent Guide

This repository is a Kotlin/Compose Desktop application for local Qwen3 text-to-speech. Read the [`docs/okf/`](docs/okf/) OKF bundle before making non-trivial changes; its `index.md` is the progressive-disclosure entrypoint and its concept files record the boundaries that are easy to break.

For OKF authoring, validation, or review, follow the project-local [`skills/okf/SKILL.md`](skills/okf/SKILL.md) and run its validator against `docs/okf/`. For multi-agent work, follow [`skills/agent-orchestration/SKILL.md`](skills/agent-orchestration/SKILL.md): one orchestrator owns the user conversation and integrates bounded subagent work.

## Working rules

- Keep UI composition in `screens/`, UI state and orchestration in `viewmodel/`, and native/runtime concerns in `engine/QwenEngine.kt` or `external/`.
- Treat `external/qwen3-tts-cpp` as a submodule boundary. Do not duplicate or substantially reimplement its inference logic in Kotlin.
- If JNI-facing C++ or submodule APIs change, rebuild the native library before testing the desktop app.
- Preserve capability-driven behavior: do not expose cloning, named-speaker, or instruction controls solely because a screen can render them.
- Preserve model/embedding compatibility checks. Equal embedding dimensions are required, but the source checkpoint is not currently persisted or verified.
- Prefer focused tests in `composeApp/src/commonTest` for pure embedding logic and state-independent behavior. Native/integration changes need a native rebuild and a desktop smoke test.
- Update the relevant concept in `docs/okf/` when a new architectural boundary, build dependency, model capability, persisted artifact, or known failure mode is introduced. Add a dated entry to `docs/okf/log.md` for material knowledge changes.

## Useful commands

```powershell
pwsh -ExecutionPolicy Bypass -File .\scripts\build-native.ps1
.\gradlew.bat :composeApp:run
.\gradlew.bat :composeApp:test
```

For CUDA, use `-Cuda` with the native build/run/package scripts. See [`docs/BUILD.md`](docs/BUILD.md) for packaging and platform-specific prerequisites.
