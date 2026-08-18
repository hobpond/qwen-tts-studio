---
type: runtime-concept
title: Model loading
description: Model-directory discovery, native model initialization, and the separate ICL prompt encoder session.
tags: [runtime, models, gguf, qwen3]
---

# Model loading

## Observed

- [`SettingsViewModel`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/SettingsViewModel.kt#L55-L130) offers tokenizer plus talker GGUF downloads for Base, CustomVoice, and VoiceDesign variants, plus the native-compatible Qwen3-ASR validation model. [`scanModelNames`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/SettingsViewModel.kt#L246-L264) lists only non-tokenizer `qwen-talker-*.gguf` files for model selection.
- The Setup UI tells the user that a model directory should contain one tokenizer GGUF and one or more talker GGUF files ([`SetupScreen.kt`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/screens/SetupScreen.kt#L233-L243)). The Kotlin wrapper itself checks only that the selected path exists and is a directory before delegating model-file validation to native code ([`loadDetailed`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt#L452-L484)).
- Native model loading receives both `modelDir` and an optional `modelName`; on success the wrapper can log the active backend. On failure it preserves the native last-error text before releasing the pointer.
- Voice workflows can reuse an already loaded session or switch between a talker session and an ICL prompt encoder session using a key containing directory, model name, backend preference, and session kind ([`VoicesViewModel.loadEngineSession`](../../../composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/VoicesViewModel.kt#L220-L244)).

## Inference / limits

- A directory can pass the Kotlin preflight and still fail in native loading because tokenizer/talker files are incomplete, incompatible, or unreadable. The exact native validation is outside this checkout.
- CLI fallback can report a successful `QwenEngine.loadDetailed` result after native model loading fails if a CLI executable is present. Callers must therefore treat “load succeeded” together with the active execution mode, not as proof that JNI loaded.

## Related concepts

- [JNI engine lifecycle](jni-engine-lifecycle.md)
- [Capability detection](capability-detection.md)
- [CLI fallback](cli-fallback.md)
- [Runtime failure modes](runtime-failure-modes.md)
