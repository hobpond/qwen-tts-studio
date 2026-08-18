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
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/engine/QwenAsrEngine.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchValidation.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchIdentity.kt
  - /composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/batch/BatchGeneration.kt
  - /external/CMakeLists.txt
  - https://github.com/QwenLM/Qwen3-TTS
  - https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice
  - https://github.com/QwenLM/Qwen3-TTS/blob/main/qwen_tts/inference/qwen3_tts_model.py
  - https://arxiv.org/abs/2601.15621
  - /scripts/render-audiobook-video.py
  - D:/work/ktv-pitcch/pipeline/src/ktv_pipeline/lyric_video.py
  - D:/work/ktv-pitcch/knowledge/research/decisions/app/d17-ktv-two-line-wipe-presentation.md
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

Each batch can contain `manifest.json` plus paired zero-padded files such as `chunk-000001.wav` and `chunk-000001.txt`. The text sidecar is the exact UTF-8 source sent for that audio chunk, making boundary and duplication problems directly inspectable; resume repairs missing sidecars from the manifest. The manifest should record text/chunk ID, internal ordinal, durable logical display index, file, frame count, sample rate, channels, PCM encoding/bit depth/endian, status, checksum, model identity, voice identity, generation parameters, and errors. Model identity should include absolute model directory/name, backend, capability record, model kind, and embedding dimension; voice identity should include mode, named speaker or canonical artifact/checksum, reference identity when applicable, dimension, language, instruction, and relevant generation parameters. Add checkpoint identity when the application can provide one.

## Batch validation

The deterministic validator checks contiguous indexes, manifest/sidecar text equality after the same whitespace cleanup used for synthesis, source round-trip equality after that normalization when the source file is available, WAV structure/metadata/checksum, completion state, and output reaching the estimated audio-token ceiling. Persisted manifest and sidecar text remains lossless, and exact text fingerprints still protect resume provenance. A cap-reaching result is a warning rather than proof of truncation. The UI exposes these findings through `Validate`.

The headless batch-verification path passes the complete `--text-file` into that same validation action for full-file runs, so a green report now includes source conservation as well as per-chunk WAV checks. A bounded `--max-chunks` run deliberately omits the full-source comparison because it validates only a selected prefix of the plan.

For a partially completed native batch, the headless runner's `--resume` action loads the durable final-plan manifest and invokes the same BatchScreen `Resume` callback. This preserves the existing adaptive chunk indexes and skips intact WAVs; rerunning the original source plan as a fresh generation would be incompatible because adaptive replanning changes the chunk count.

Manifest and combined-WAV atomic moves retry a short, bounded sequence when Windows reports a transient `AccessDeniedException` during replacement. Other filesystem failures remain fatal, and the temporary file is still cleaned up, so the retry handles scanner/read races without weakening persistence integrity.

The generation boundary now computes one `maxAudioTokens` budget from the cleaned synthesis text, native text-token count when available, instruction context, and voice mode, then persists it as `audioCapTokens.<chunkIndex>` in manifest metadata. Validation consumes that exact original-chunk input instead of recomputing a different raw-text estimate; malformed metadata is an error, while legacy manifests without the additive key report `AUDIO_CAP_INPUTS_UNAVAILABLE` and do not invent a replacement estimate. This metadata is preserved when a compatible manifest is resumed.

When native output approaches the budget, sentence-boundary retry parts are allocated from the remaining budget of the original logical chunk. A part that reaches its allocation, a failed part, a missing boundary, or an aggregate result at or above the original sample cap fails the chunk before WAV persistence; audio is never trimmed to make an over-budget result appear valid. This is a fail-closed aggregate-cap boundary.

Fresh agent and Studio requests opt into a bounded adaptive audio replan before native generation. The planner uses the measured text-token count, instruction context, voice mode, and native limits; it keeps at least 569 context tokens of headroom and targets a conservative 384-token duration budget. Replanning is lossless: the final manifest text chunks remain contiguous substrings of the source, and the final manifest's count and per-chunk voice metadata are authoritative. Near-limit whitespace splits are rebalanced to avoid tiny tails, and short heading or tail pieces are merged with an adjacent chunk only when the combined piece remains below the native 512-token floor with context headroom.

The Chinese/Vivian headless wrapper adds a validated reliability profile on top of the memory plan: when no explicit chunk size is supplied, it targets 80 source characters. A 40-character target remains the failed-chunk recovery fallback. The bounded CUDA/ASR probe showed that 80-character requests are materially more efficient while remaining within the observed reliability envelope; 120-character requests produced materially more ASR/cap failures. Punctuation-only fragments, including a closing quote split from its clause, are losslessly attached to neighboring speech text before native submission.

Adaptive replanning is fresh-batch-only. It is rejected before native work when a compatible manifest, chunk WAV/text artifacts, selected/regenerated indexes, or mixed per-chunk voices would make arbitrary renumbering unsafe; the existing directory is left untouched and the user must choose a fresh output directory. Resume and replay therefore use the exact durable final plan rather than silently changing chunk identity. A native CUDA/Vivian proof on 2026-08-15 changed three source chunks into ten final chunks, generated all ten without the aggregate guard, passed deterministic and ASR validation, and recombined `combined.wav`.

Failed-chunk recovery is the controlled exception to that fresh-batch rule. `BatchAudioStore.rechunkFailedChunks` updates the existing manifest only when the supplied snapshot is current, validates every unaffected complete WAV, stages all chunk artifacts, and writes a timestamped `manifest.before-rechunk-*.json` backup before committing the new plan. A failed or validation-failed source labelled `15` is losslessly split into children labelled `15.1`, `15.2`, and so on; a short failed tail is merged with adjacent source text when the recovery limit permits, because a one- to six-character standalone ASR unit is not a reliable validation target. Unaffected logical labels remain stable even though their internal zero-based ordinal and filename may shift, and a merge may reduce the expected row count. The internal ordinal preserves existing engine, validator, and file-name contracts. New or merged retry units are `PENDING`, complete audio is copied only when its text remains unchanged, failed-source cap keys are discarded, unaffected cap keys are remapped, and the new ordered text fingerprint is persisted before the normal manifest-driven Resume action runs.

Probabilistic validation is represented by a `BatchAsrTranscriber` adapter and prefix/suffix comparison model. `NativeBatchAsrTranscriber` loads a Qwen3-ASR GGUF through `QwenAsrEngine`, which is compiled into the same JNI DLL and shares the application's GGML backend build. The Kotlin adapter converts generated WAV audio to the ASR model's 16 kHz mono PCM contract, crops the first and last five seconds, and sends samples directly through JNI. The ASR model is an optional managed download, so deterministic validation remains available when it is not installed; ASR errors are surfaced as validation findings rather than silently substituted.

The native build includes `external/qwen3-asr-cpp` as a pinned source submodule. It links the ASR static library against the TTS build's GGML targets, producing one `qwen3_tts.dll`; no ASR subprocess or Python runtime is required. `scripts/build-native.ps1 -NoCopyToRoot` is available when the development DLL is locked by a running application.

ASR backend selection is explicit rather than an environment-variable side effect. `QwenAsrEngine` defaults to CPU; `NativeBackendPreference.Cuda` selects CUDA for both the audio encoder and text decoder and requires their weights to be placed on the device. A CUDA request fails closed when no compatible GPU or device-resident weights are available. Batch validation returns backend evidence including the native backend name, GPU-active state, encoder/decoder weight placement, and free/total device memory. The agent's `--backend cuda` option and the headed Batch screen's selected backend preference both pass this choice into ASR validation; direct callers that omit it retain the safe CPU default.

## Sequential validation and streaming admission

The default buffered batch path still overlaps one disk write with the next TTS request and performs the explicit full-batch validation action afterward. An opt-in queued policy, exposed by the headed Batch workflow when the ASR model is installed and by headless `--queued-validate`, changes the lifecycle to `generate -> persist WAV/text -> enqueue chunk index -> continue TTS`; one ASR worker consumes the durable queue and publishes each result independently. `--sequential-validate` and `--incremental-validate` remain aliases. One `NativeBatchAsrTranscriber` is loaded for the run and reused for each queued chunk; the TTS producer does not wait for an individual ASR result, and only experiences backpressure when the bounded queue is full. A blocking mode remains available to direct callers that require strict per-chunk admission.

Each chunk's `validationPassed`, message, and validation signature are persisted immediately after its worker result. The headed screen reports persisted, generated, and validated counts and permits playback of a durable complete chunk while later chunks or queued validation are still running. The headless report records `sequentialValidation` and `validationRetries`; queued headless runs perform a fresh deterministic/source audit at `validate-all` and reuse persisted ASR admissions only when every row is complete, its validation signature still matches, and the ASR model fingerprint is unchanged. Otherwise `validate-all` performs the full ASR audit. The headed validation action remains a full audit by default.

The retry budget applies after queued results arrive. A validation failure re-enters the same logical chunk in a later TTS retry round, up to `validationRetries`; later chunks may already be durable and playable. A persistent failure marks the run failed without renumbering or silently re-chunking text. A transient native no-audio failure still retries immediately because there is no audio artifact to enqueue. For a context/capacity failure that requires a smaller logical piece, the manifest-driven `rechunkFailedChunks` recovery path remains the explicit decimal-index fallback.

The available VRAM evidence does not show an out-of-memory requirement for one resident ASR worker: ASR is device-backed when CUDA is selected, and the observed ASR/TTS-resident runs retained free device memory. The queued mode deliberately uses one ASR worker and a bounded index queue; it does not create concurrent ASR workers. The separate ASR concurrency probe remains diagnostic, and its TTS-active profile does not yet establish a general native thread-safety guarantee. Native queued overlap therefore remains an explicit runtime acceptance check, not a claim that all native DLL calls are thread-safe.

Manifest persistence is a shared control-plane boundary on Windows. Every batch directory has a stable `.manifest.lock` file; writers take a short JVM-and-process-shared file lock around manifest read-modify-write operations and atomic replacement. The manifest carries a top-level monotonic `manifestRevision`, and stale whole-snapshot writes fail with a manifest conflict instead of silently erasing a queued validation or another store's chunk update. Rechunking stages files without holding the lock, then performs its artifact swap and revision-checked manifest commit inside one short transaction; a conflict leaves the staged migration uncommitted.

Queued ASR captures a `BatchChunkValidationLease` containing the integer index, decimal display label, exact text, generation inputs, WAV filename, checksum, sample rate, and frame count. Publication compares that lease against the current row under the same manifest lock. If TTS completion, voice reconfiguration, rechunking, or another writer replaced the row while ASR was running, the result is reported as stale and is discarded; the batch fails closed and must reload/resume. This prevents a validation result for an old WAV from marking a newly rechunked child as passed.

## Manifest provenance

Native batch manifests use `manifestIdentityVersion: 2` and record the selected TTS model file's absolute path, byte size, SHA-256, and fingerprint algorithm. They also record the loaded JNI library (`qwen3_tts.dll`/platform equivalent), its size and SHA-256, the native root, active/compiled backend, application/runtime identifiers, model capability snapshot, and each loaded GGML/CUDA dependency's role, path, size, and SHA-256. A stable aggregate `nativeRuntimeFingerprint` makes the complete native distribution easy to compare while the individual entries keep the mismatch inspectable. The ASR validation action appends the ASR model path, size, and SHA-256 to the same manifest.

Model and native-runtime fingerprints participate in resume compatibility. A changed selected model or loaded native distribution fails closed before new audio is generated; explicit incompatible replacement remains the only way to start over in an existing output directory. Paths are retained as provenance, while hashes are the evidence of content identity. Model hashing is streamed rather than materialized as one large byte array.

## Recombination and recovery invariants

- Establish one exact format before generation; the initial safe target is mono, signed 16-bit little-endian PCM WAV, but sample rate must be verified from native output rather than assumed.
- Concatenate by numeric manifest index, never filesystem order. Require every expected index; do not insert silence for gaps or trim overlaps implicitly.
- Treat `displayIndex` as a durable logical label for user-facing recovery history, but use contiguous numeric `index` for storage order and recombination. Legacy manifests without `displayIndex` decode it as the numeric index string.
- Verify every chunk's header, format, frame count, checksum, model identity, voice identity, and completion state before writing a combined WAV. Fail closed for missing, failed, cancelled, corrupt, or incompatible chunks.
- Recombination streams chunk PCM and keeps the aggregate byte count in a 64-bit value. Standard RIFF/WAV size fields are unsigned 32-bit, so payloads above Java's signed `Int` limit but below the 4 GiB RIFF ceiling remain valid; larger outputs fail closed with an explicit format-limit error.
- Stop scheduling new chunks on cancellation; preserve completed files; write chunks and manifest updates through temporary files plus atomic rename. Retry policy must make sampling nondeterminism explicit.
- Do not treat streaming callbacks as recombinable batch chunks until `startSample`/`endSample`, overlap policy, channels, and PCM format are established by native evidence.

## Audiobook sweep video export

The manifest-driven backend exporter at `/scripts/render-audiobook-video.py` adapts the local KTV renderer for audiobook learning. It uses the validated manifest as the control plane, sums each chunk's `frameCount / sampleRate` interval, and muxes the existing `combined.wav`; it never regenerates TTS or reads a second source-text copy. `scripts/render-audiobook-video.ps1` is the Windows entrypoint.

The video uses a dark background and a centered multi-line reading window. The default is seven fixed rows (`--lines-on-screen 7`): three preceding lines are sung white context, the active line remains centered with ASS `\\kf` smooth-fill tags, and three following lines are blue read-ahead. The odd row count can be set from 3 through 11; the wrapper defaults to a 36px font at 1280x720 so more audiobook text stays visible without making the characters too small. Source whitespace is normalized only for presentation (BOM removed, Unicode whitespace collapsed to one space); the manifest's source text remains unchanged.

The exporter labels its timing source `uniform-character-within-chunk`: the current manifest has chunk-level audio frames but no measured character alignments, so the sweep is an explicit visualization estimate rather than a phoneme-timing claim. It writes the `.ass` sidecar and a JSON report containing manifest identity, model/voice provenance references, text fingerprints, layout settings, stream probes, and pixel counts. A render is not accepted until ffprobe sees exactly one H.264 video stream and one AAC audio stream and a raw-frame probe observes a non-decreasing net rise in sung pixels across the longest active-line sweep. A bounded `--duration-limit` proof and an unbounded full export use the same plan and checks; a real 90-second seven-row proof passed at 1280x720 before the full export was started.

Long exports use parallel video-only segments by default (`--segment-duration 600`, `--segment-workers 2`). Boundaries are moved to display-line starts so each segment has a complete local active-line sweep; the segment ASS files are rendered concurrently, joined with the ffmpeg concat demuxer using `-c copy`, and the original combined WAV is encoded to AAC exactly once during the final mux. This avoids repeating a large audio encode per segment and keeps the WAV/manifest timing authoritative. `--segment-duration 0` retains the monolithic fallback, while `--keep-segments` preserves intermediate segment ASS/video files for diagnosis or later re-joining. The JSON report records the segment count, worker count, boundaries, join method, and single-mux policy.

The resumable delivery mode is enabled with `--streamable` (also available as `-Streamable` through the PowerShell wrapper). It first creates or reuses `<output>.audio.m4a` plus a sidecar containing the source WAV size/mtime, audio format, bitrate, and target duration. The final MP4 uses `empty_moov`/fragmented-MP4 flags and copies the cached AAC and joined H.264 streams, so retrying assembly does not re-encode the multi-hour WAV. It also writes `<output-stem>-hls/<output-stem>.m3u8` with fMP4 segments and a transactional `init.mp4`; the report records both delivery artifacts and verifies their references. Streamable runs retain line-aligned video segments and a parameter/source fingerprint in `segments.json`, allowing a compatible rerun to reuse completed video segments. This is a resumable streamable delivery package after the manifest-backed artifacts exist; it is not yet live TTS-to-player transport.

## Application orchestration contract

The platform-neutral contract at `/composeApp/src/commonMain/kotlin/com/qwen/tts/studio/orchestration/AudiobookPipelineContract.kt` is the shared command/event/snapshot seam for headed UI, headless instrumentation, and future streaming workers. It carries run and command IDs, manifest revisions, leases, provenance, artifact handles, chunk lifecycle, validation state, generated/playable/validated/visual-ready distinctions, and explicit start/resume/cancel/retry/rechunk/validate/combine/render/stream commands. `PipelineSnapshot.apply` and `PipelineCommandGuards` provide common stale-event and stale-command behavior so clients reconcile one durable control plane.

The desktop core at `/composeApp/src/desktopMain/kotlin/com/qwen/tts/studio/orchestration/DesktopAudiobookPipelineOrchestrator.kt` serializes ordinary commands, gives cancellation a separate control path, reduces executor events into a `StateFlow`, and exposes command evidence without owning Qwen engines or mutating manifests. The UI-facing `BatchPipelineBinding` forwards the same snapshot/event stream to Compose or instrumentation scopes. Concrete executors remain responsible for calling the existing `StudioViewModel`/`BatchAudioStore` workflow, preserving native lifetime, manifest locks, and validation leases.

## Remaining native verification limits

The native submodule remains authoritative for whether sequential calls reuse loaded model state efficiently, whether voice artifacts are internally reloaded or cached, and what native channel/PCM contract is guaranteed. The Kotlin boundary now retains the native sample rate and writes an explicit mono PCM16 output contract; it does not claim undocumented native internals.

## Implemented boundary

`BatchGenerationSession` now performs one explicit `QwenEngine.loadDetailed` call, rejects CLI fallback, sends sequential buffered requests through `generateDetailed`, and writes each successful result through `BatchAudioStore`. `StudioViewModel.startBatchGeneration` exposes both an immutable request overload and a convenience overload that snapshots the current Studio voice/capability state. `BatchAudioStore` persists atomic manifest updates, includes model/voice/audio metadata, hashes each WAV, and requires all chunks to be complete, numerically contiguous, checksum-valid, mono PCM16, and at one sample rate before recombination.

The portable Studio screen loads a UTF-8 text file, greedily packs complete blank-line-delimited paragraphs together up to the 5,000-character Studio limit while preserving paragraph breaks, and splits only an individual oversized paragraph. It chooses an output directory, starts/cancels the batch, reports callback-driven completed-chunk progress, and offers recombination to a WAV. `BatchEngine`/`QwenBatchEngine` separates the session from the concrete native wrapper; desktop tests use that seam to prove one load followed by sequential generation without requiring a GGUF model. Real portable inference remains a manual acceptance test and is not implied by the fake-engine evidence.

Lifecycle hardening serializes ViewModel teardown on the same native dispatcher used for synthesis. Batch results retain their normalized output directory; recombination has separate UI state, rejects chunk-file overwrite targets, and preserves generation completion when assembly fails. Per-chunk generation and storage exceptions are recorded as failed manifest entries. A cancellation request after the final complete chunk does not downgrade a valid completed batch.

The native submodule is still the source of truth for whether its internal model and voice data are efficiently reused between sequential JNI calls. The application-level contract guarantees that Kotlin does not reload or release between chunks; it does not invent a native caching guarantee.

## Resume and memory policy

Batch starts are resumable when the output directory contains a compatible manifest. The output directory is the durable batch identity; transient UI request IDs do not have to survive an application restart. Compatibility requires the same chunk count, model/voice metadata, and SHA-256 fingerprint of the ordered chunk texts. Existing chunk files are scanned and parsed as WAVs; only intact, valid files are marked complete and skipped. Missing, corrupt, or incompatible output is regenerated, while unrelated output is not adopted.

The memory policy is sequential and disk-backed: the current native audio result and at most one encoded chunk are held during generation; the persistence worker may write the previous chunk while native generation computes the next, and recombination validates chunks in one pass before streaming them one at a time into the combined WAV. Before generation, Studio queries the active GGML backend's generic device memory provider and the JVM's available system memory, then chooses a conservative text target from the lower of those limits. Each chunk also receives an audio-token ceiling estimated from its cleaned text, native text-token count, instruction context, and voice mode, bounded by the native model maximum. This is backend/OS agnostic and does not alter sampling temperature. A bounded one-slot persistence pipeline encodes and atomically writes the previous chunk while native generation computes the next; manifest commits remain ordered and no concurrent native generations are attempted. A replayed manifest can force selected completed indexes to regenerate while retaining all other valid chunks. The implementation does not claim that multiple concurrent native generations are safe. Adaptive replanning is recorded with `adaptiveAudioReplanVersion` and `adaptiveAudioReplanSourceChunks` metadata so evidence can distinguish the requested source plan from the durable final plan.

ASR concurrency is investigated separately by the process-isolated [ASR concurrency testing](../operations/asr-concurrency-testing.md) runner. It keeps multiple ASR engines loaded, compares synchronized parallel work to a serial baseline, and requires native backend and device-memory evidence before a CUDA result can be gate-eligible. This diagnostic path does not change the serialized production validator or imply that concurrent TTS generation is safe; the native TTS backend retains process-wide shared state.

## Named-speaker and instruction reuse

CustomVoice models use a different reusable-voice path from Base models. When the loaded model reports named-speaker capability, `StudioViewModel.startBatchGeneration` snapshots the selected speaker and current instruction directly into each buffered request. The batch session loads the CustomVoice model once, then sends the same `speaker` and `instruction` for every text chunk. No captured WAV, embedding, or VoiceDesign snapshot is required. The Studio UI describes this as “Batch will reuse [speaker]” rather than presenting a disabled clone-save control.

This repeats the model's named timbre and conditioning instruction; it does not claim sample-identical prosody. VoiceDesign previews still require the separate VoiceDesign-to-Base clone-prompt workflow described below when a fixed reusable voice artifact is wanted.

## Qwen3-TTS CustomVoice prompting guidance

The source-grounded guidance in this section applies to the Qwen3-TTS 1.7B CustomVoice family used by the local GGUF workflow. CustomVoice combines a predefined named speaker with optional natural-language style control; it is not the VoiceDesign or Base-model cloning path. The 1.7B CustomVoice model supports instruction following, while the official inference implementation disables `instruct` for the 0.6B CustomVoice variant. The native GGUF wrapper may expose fewer sampling controls than the Python reference, so the Python defaults are reference behavior rather than a claim about every local runtime build.

Pass the instruction as plain natural language. The official implementation wraps it in its own chat template, so callers must not add ChatML markers such as `<|im_start|>` or `<|im_end|>`. Keep the same instruction for every chunk in a batch; changing it per chunk changes delivery conditioning even when the named speaker stays constant.

For English audiobook narration with Vivian, the preferred baseline is:

> Read as a polished audiobook narrator in a calm, warm, measured style, with clear diction, natural sentence emphasis, subtle emotional variation, and brief pauses at punctuation.

Useful controlled variants change one dimension at a time:

- Tension: `Read with restrained urgency and rising tension, slightly faster pacing, crisp diction, and stronger emphasis on emotionally important words.`
- Intimacy: `Read softly and intimately, with gentle warmth, slower pacing, relaxed phrasing, and understated emotion.`

Avoid stacking conflicting adjectives. Use `dramatic` only when the source scene calls for it; a shorter, coherent instruction generally makes chunk-to-chunk comparison easier. Explicit language selection is preferred when the language is known. Vivian is documented as a bright, slightly edgy young female voice with Chinese as its native language; Ryan and Aiden are better starting points for native English male delivery when speaker identity is not otherwise constrained.

Punctuation and paragraph structure remain part of the practical delivery control because they provide boundaries for phrasing and pauses. The prompt controls style and delivery, not exact prosody: deterministic validation should prove text conservation, manifest/sidecar agreement, WAV integrity, and aggregate-cap safety, while ASR checks words at the edges and human listening remains necessary for voice quality, rhythm, and expressive fit. The Qwen3-TTS technical report also cautions that ASR is not a complete perceptual-quality judge.

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
