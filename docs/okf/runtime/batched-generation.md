---
okf_version: 0.2
type: runtime-concept
title: Reusable batched generation
description: Evidence and proposed boundary for generating ordered audio chunks without reloading model and voice state.
tags: [runtime, batching, audio, voices, design]
status: active
generated:
  by: codex
  at: 2026-08-03T00:00:00Z
sources:
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/viewmodel/StudioViewModel.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenEngine.kt
  - /external/CMakeLists.txt
---

# Reusable batched generation

## Current evidence

- `StudioViewModel.generateAudio` calls `QwenEngine.loadDetailed` before a generation, and `loadDetailed` releases the previous native pointer before loading. Current Studio therefore reloads the model per request.
- Voice inputs are passed as a speaker name or artifact path on each synthesis call; there is no persistent Kotlin/native voice object in the current facade.
- The native submodule source is absent in this checkout, so native model reuse, voice caching, thread safety, channel layout, and exact PCM guarantees are unknown.
- CLI fallback launches a new process and temporary WAV path per request. It cannot meet a one-loaded-session batch guarantee.
- Non-streaming generation loses the native sample rate at the Kotlin facade; Studio writes a signed little-endian 16-bit mono WAV using a 24 kHz assumption. Streaming callback ranges expose sample positions, but Studio currently appends callback arrays without proving gaps or overlaps are absent.

## Proposed first implementation boundary

Add a `BatchSession` above individual synthesis calls with immutable model identity, voice identity, audio format, defaults, and ordered chunk records. Load the model once, freeze model/backend/capability/language/voice/instruction/parameters, and execute sequentially on the existing single-thread executor. Reject model or voice changes during the batch. Initial support should be native JNI buffered generation; CLI fallback should be explicitly unsupported for the no-reload guarantee.

Each batch can contain `manifest.json` plus paired zero-padded files such as `chunk-000001.wav` and `chunk-000001.txt`. The text sidecar is the exact UTF-8 source sent for that audio chunk, making boundary and duplication problems directly inspectable; resume repairs missing sidecars from the manifest. The manifest should record text/chunk ID, index, file, frame count, sample rate, channels, PCM encoding/bit depth/endian, status, checksum, model identity, voice identity, generation parameters, and errors. Model identity should include absolute model directory/name, backend, capability record, model kind, and embedding dimension; voice identity should include mode, named speaker or canonical artifact/checksum, reference identity when applicable, dimension, language, instruction, and relevant generation parameters. Add checkpoint identity when the application can provide one.

## Batch validation

The deterministic validator checks contiguous indexes, manifest/sidecar text equality, source round-trip equality when the source file is available, WAV structure/metadata/checksum, completion state, and output reaching the estimated audio-token ceiling. A cap-reaching result is a warning rather than proof of truncation. The UI exposes these findings through `Validate`.

Probabilistic validation is represented by a `BatchAsrTranscriber` adapter and prefix/suffix comparison model. `NativeBatchAsrTranscriber` loads a Qwen3-ASR GGUF through `QwenAsrEngine`, which is compiled into the same JNI DLL and shares the application's GGML backend build. The Kotlin adapter converts generated WAV audio to the ASR model's 16 kHz mono PCM contract, crops the first and last five seconds, and sends samples directly through JNI. The ASR model is an optional managed download, so deterministic validation remains available when it is not installed; ASR errors are surfaced as validation findings rather than silently substituted.

The native build includes `external/qwen3-asr-cpp` as a pinned source submodule. It links the ASR static library against the TTS build's GGML targets, producing one `qwen3_tts.dll`; no ASR subprocess or Python runtime is required. `scripts/build-native.ps1 -NoCopyToRoot` is available when the development DLL is locked by a running application.

## Recombination and recovery invariants

- Establish one exact format before generation; the initial safe target is mono, signed 16-bit little-endian PCM WAV, but sample rate must be verified from native output rather than assumed.
- Concatenate by numeric manifest index, never filesystem order. Require every expected index; do not insert silence for gaps or trim overlaps implicitly.
- Verify every chunk's header, format, frame count, checksum, model identity, voice identity, and completion state before writing a combined WAV. Fail closed for missing, failed, cancelled, corrupt, or incompatible chunks.
- Stop scheduling new chunks on cancellation; preserve completed files; write chunks and manifest updates through temporary files plus atomic rename. Retry policy must make sampling nondeterminism explicit.
- Do not treat streaming callbacks as recombinable batch chunks until `startSample`/`endSample`, overlap policy, channels, and PCM format are established by native evidence.

## Remaining native verification limits

The native submodule remains authoritative for whether sequential calls reuse loaded model state efficiently, whether voice artifacts are internally reloaded or cached, and what native channel/PCM contract is guaranteed. The Kotlin boundary now retains the native sample rate and writes an explicit mono PCM16 output contract; it does not claim undocumented native internals.

## Implemented boundary

`BatchGenerationSession` now performs one explicit `QwenEngine.loadDetailed` call, rejects CLI fallback, sends sequential buffered requests through `generateDetailed`, and writes each successful result through `BatchAudioStore`. `StudioViewModel.startBatchGeneration` exposes both an immutable request overload and a convenience overload that snapshots the current Studio voice/capability state. `BatchAudioStore` persists atomic manifest updates, includes model/voice/audio metadata, hashes each WAV, and requires all chunks to be complete, numerically contiguous, checksum-valid, mono PCM16, and at one sample rate before recombination.

The portable Studio screen loads a UTF-8 text file, greedily packs complete blank-line-delimited paragraphs together up to the 5,000-character Studio limit while preserving paragraph breaks, and splits only an individual oversized paragraph. It chooses an output directory, starts/cancels the batch, reports callback-driven completed-chunk progress, and offers recombination to a WAV. `BatchEngine`/`QwenBatchEngine` separates the session from the concrete native wrapper; desktop tests use that seam to prove one load followed by sequential generation without requiring a GGUF model. Real portable inference remains a manual acceptance test and is not implied by the fake-engine evidence.

Lifecycle hardening serializes ViewModel teardown on the same native dispatcher used for synthesis. Batch results retain their normalized output directory; recombination has separate UI state, rejects chunk-file overwrite targets, and preserves generation completion when assembly fails. Per-chunk generation and storage exceptions are recorded as failed manifest entries. A cancellation request after the final complete chunk does not downgrade a valid completed batch.

The native submodule is still the source of truth for whether its internal model and voice data are efficiently reused between sequential JNI calls. The application-level contract guarantees that Kotlin does not reload or release between chunks; it does not invent a native caching guarantee.

## Resume and memory policy

Batch starts are resumable when the output directory contains a compatible manifest. The output directory is the durable batch identity; transient UI request IDs do not have to survive an application restart. Compatibility requires the same chunk count, model/voice metadata, and SHA-256 fingerprint of the ordered chunk texts. Existing chunk files are scanned and parsed as WAVs; only intact, valid files are marked complete and skipped. Missing, corrupt, or incompatible output is regenerated, while unrelated output is not adopted.

The memory policy is sequential and disk-backed: the current native audio result and at most one encoded chunk are held during generation; the persistence worker may write the previous chunk while native generation computes the next, and recombination validates chunks in one pass before streaming them one at a time into the combined WAV. Before generation, Studio queries the active GGML backend's generic device memory provider and the JVM's available system memory, then chooses a conservative text target from the lower of those limits. Each chunk also receives an audio-token ceiling estimated from its character count, bounded by the native model maximum. This is backend/OS agnostic and does not alter sampling temperature. A bounded one-slot persistence pipeline encodes and atomically writes the previous chunk while native generation computes the next; manifest commits remain ordered and no concurrent native generations are attempted. A replayed manifest can force selected completed indexes to regenerate while retaining all other valid chunks. The implementation does not claim that multiple concurrent native generations are safe.

## Named-speaker and instruction reuse

CustomVoice models use a different reusable-voice path from Base models. When the loaded model reports named-speaker capability, `StudioViewModel.startBatchGeneration` snapshots the selected speaker and current instruction directly into each buffered request. The batch session loads the CustomVoice model once, then sends the same `speaker` and `instruction` for every text chunk. No captured WAV, embedding, or VoiceDesign snapshot is required. The Studio UI describes this as “Batch will reuse [speaker]” rather than presenting a disabled clone-save control.

This repeats the model's named timbre and conditioning instruction; it does not claim sample-identical prosody. VoiceDesign previews still require the separate VoiceDesign-to-Base clone-prompt workflow described below when a fixed reusable voice artifact is wanted.

## Accepted Read Aloud voice capture

Instruction-driven Read Aloud/Streaming synthesis does not return a reusable VoiceDesign object. `StudioViewModel.captureCurrentVoiceForBatch` therefore writes the last completed preview to the OS temporary directory, extracts a speaker-embedding JSON through the existing native extraction API, and stores that artifact in `StudioUiState.reusableVoiceSnapshot`. The capture records normalized model directory/name, backend, embedding dimension, and the source style instruction as provenance.

When a captured snapshot is supplied to `startBatchGeneration`, the immutable request uses its speaker-embedding path for every chunk and sets `instruction` to null. This is deliberate: resubmitting the VoiceDesign instruction would design a new voice per chunk. The manifest records `voiceMode: speaker-embedding` and `voiceProvenance`; the artifact is ephemeral and is removed when the Studio view model is cleared or the user clears it. Capture is rejected at batch start when the requested model identity differs from the snapshot.

This freezes speaker identity through the existing reusable artifact path. It does not promise identical prosody or a native first-class VoiceDesign handle; those remain native-model limitations.

The captured snapshot can be promoted from temporary state to a named `VoicePreset` from Studio. The WAV is copied to managed recordings, the embedding is extracted and persisted through `voice-presets.tsv`, and the new preset is selected for subsequent generation. Restart/reload uses the existing preset loader; checkpoint identity is still not persisted, so dimension equality is not proof of semantic checkpoint compatibility.

For an instruction-driven VoiceDesign preview, the full path is two-stage: Studio retains the accepted preview WAV and its exact preview text, then `VoicesViewModel.createVoicePreset` loads a compatible Base talker and extracts the existing full ICL prompt. Studio switches to that Base model and ICL mode before later batch requests. This matches Qwen's documented VoiceDesign-then-Clone workflow; the VoiceDesign instruction remains provenance and is not sent into Base-model batch chunks.

## Related concepts

- [Synthesis, streaming, and playback](synthesis-streaming-playback.md)
- [Studio workflow](../workflows/studio.md)
- [Voice preset lifecycle](../workflows/voice-presets.md)
- [Native runtime overview](native-runtime.md)
