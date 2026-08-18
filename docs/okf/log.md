# OKF Update Log

## 2026-08-17

* **Recovery hardening**: Failed-chunk recovery now rebalances short tails, merges failed one- to six-character children with adjacent speech when the recovery budget allows, preserves persisted validation state across resume reconstruction, and lets queued headless completion reuse matching per-chunk ASR admissions after a deterministic/source audit instead of repeating a full ASR scan.
* **Recombination fix**: Large combined WAVs now keep aggregate PCM accounting in 64-bit space and encode RIFF's unsigned 32-bit size fields correctly, so a valid sub-4 GiB output is not rejected at Java's signed `Int` boundary.
* **Manifest-only validation**: Added `--validate-manifest-only` for complete batches whose TTS runtime fingerprint must not be rewritten or resumed. The instrumentation loads the durable manifest, runs the current resident-ASR validation action, persists ASR provenance and row results, then recombines without regenerating audio.

* **Manifest concurrency**: Added a stable Windows-friendly `.manifest.lock`, top-level monotonic `manifestRevision` compare-and-swap, atomic merge helpers across store instances, and fail-closed `BatchChunkValidationLease` publication. Rechunk commits the artifact swap and manifest update under the short shared lock; stale queued ASR or TTS completions cannot overwrite a newer row or mark a reused integer index as validated.

* **Queued validation**: Added an opt-in generate â†’ persist â†’ queue â†’ validate lifecycle with one resident ASR worker, bounded index backpressure, same-logical-chunk retry rounds, durable per-chunk validation state, and headed playback availability for already persisted chunks. Headless `--queued-validate`/`--validation-retries N` now drives and reports the same path; VRAM evidence does not show an OOM requirement, while native TTS/ASR thread-safety remains an explicit acceptance check.
* **Recovery**: Added manifest-driven failed-chunk rechunking. `BatchAudioStore.rechunkFailedChunks` transactionally stages artifacts, validates unaffected complete WAVs, writes a recoverable pre-rechunk manifest, splits failed source chunks into decimal `displayIndex` children, remaps unaffected audio-cap metadata, and updates the ordered text fingerprint before Resume.
* **Instrumentation**: Added the headed and headless `rechunk-failed` action plus `--rechunk-failed --rechunk-characters N`; the fake UI and runner tests prove load → rechunk → resume → validate → combine completion without computer use. Legacy manifests default `displayIndex` to their numeric index.
* **Recovery fix**: A failed source chunk at or below the requested rechunk limit is now retained as one pending retry child instead of aborting the whole migration; this covers short ASR mismatches and cap failures alongside decimal splits.
* **Chunk profile/probing**: Promoted the Chinese/Vivian normal target from 40 to 80 source characters while retaining 40 for failed-chunk recovery. Added a repeated stratified probe over prefix/middle/suffix windows and multiple rounds; each fresh session proves the bounded input prefix, manifest/report count agreement, and combined-WAV existence before it can recommend a size. It reports cap, ASR, native-failure, timing, and device-memory evidence and only recommends a size when every run passes.

## 2026-08-16

* **UI usability**: The headed Batch surface now uses a bounded lazy chunk list for large manifests, semantic search across index/text/voice, all/needs-attention/complete/validated filters, visible-versus-total counts, and a toolbar Combine action. A 320-chunk Compose instrumentation test proves distant-chunk discovery without eager row composition and preserves the compact one-chunk workflow.
* **Naming**: Renamed the primary user-facing agent workflow to headless batch verification. The application now exposes `--headless-batch-verify`, defaults to `batch-verification-report.json`, and uses a descriptive temporary output name; `--agent-smoke` and `agent-report.json` remain compatibility aliases for existing automation.
* **ASR diagnosis and repair**: Added a bounded `--agent-asr-inspect` runner that records exact prefix/suffix transcripts for selected existing chunks without mutating the manifest, plus `--resume --regenerate-chunks` support that drives the BatchScreen per-row regeneration action before the normal full validation/combine gate. A repaired Vivian run from `D:\\t1\\t1.txt` regenerated chunks 23 and 112, passed all 369 chunks and 738 CUDA ASR windows, and produced a verified combined WAV.
* **Manifest-first control plane**: Agent resume now accepts `--manifest PATH --resume` without source text, chunking, model, voice, prompt, or backend overrides. It reads the persisted plan and provenance from the manifest, rejects conflicting overrides, validates/recombines the manifest directly, and keeps an optional source file limited to round-trip verification.
* **Manifest provenance**: Native batch manifests now persist streamed SHA-256 fingerprints and sizes for the selected TTS model, loaded JNI library, GGML/CUDA dependencies, and validated ASR model, plus backend/capability/runtime identifiers and an aggregate native-runtime fingerprint.
* **Resume safety**: Model/native-runtime fingerprints participate in compatibility checks, so an existing batch fails closed before new audio when its model or loaded native distribution differs; the recording instrumentation delegates the runtime identity instead of dropping it.
* **Verification**: Fresh native CUDA manifest `asr-cuda-provenance-2-20260816` passed 8/8 generation and ASR validation and contains 92 metadata entries, including TTS model SHA-256, `qwen3_tts.dll` SHA-256, seven dependency fingerprints, ASR model SHA-256, `CUDA0`, and model capability fields.
* **ASR CUDA fix**: Rebuilt the native ASR path with explicit backend selection. The default remains CPU, while an agent/native `--backend cuda` request now selects CUDA for both encoder and decoder, requires device-resident weights, and fails closed when GPU residency cannot be proven.
* **ASR evidence**: Batch validation and `agent-report.json` now expose native backend name, GPU-active state, encoder/decoder weight placement, free/total device memory, validation-window count, chunk count, and ASR elapsed time instead of inferring VRAM use from a request flag.
* **Verification**: A rebuilt native CUDA round from `D:\\t1\\t1.txt` passed generation, deterministic validation, ASR validation, and recombination; ASR reported `CUDA0`, both weight-placement flags true, 16 windows across 8 chunks, 3,131 ms total ASR time, and device memory telemetry separate from host RAM.
* **Prompting guidance**: Added source-grounded Qwen3-TTS CustomVoice guidance for 1.7B instruction support, plain-natural-language instructions, named-speaker/language selection, stable per-batch prompts, Vivian audiobook baselines, and the boundary between deterministic/ASR validation and human listening.
* **Instrumentation**: Full text-file agent runs now pass the original source into the existing BatchScreen validation action, proving source round-trip conservation in the report; bounded prefix runs remain explicit subset validations.
* **Instrumentation**: Added a headless `--resume` mode that loads an existing final-plan manifest before invoking the BatchScreen Resume action, allowing long native batches to continue without adaptive renumbering.
* **Persistence**: Atomic manifest and combined-WAV replacement now retries transient Windows `AccessDeniedException` locks a bounded number of times before failing closed.
* **UI instrumentation**: Added an in-process headed Compose validation seam: injectable App view models, deterministic Batch file/save callbacks, stable semantic test targets, observable operation state descriptions, and a desktop UI test covering navigation, text selection, generation, manifest reload, validation, and recombination without OS input or computer use.
* **ASR concurrency instrumentation**: Added process-isolated `--agent-asr-concurrency` profiles that keep one or more ASR engines loaded, synchronize serial/parallel rounds, capture backend and device-memory evidence, compare transcripts to a serial baseline, and fail closed on unsafe CUDA conditions. The production batch validator remains serialized until the CUDA gate is satisfied and reviewed.
* **Native runtime selection**: The ASR profile wrapper can now pass `-PnativeRuntimeRoot` to the desktop run task, selecting the exact DLL/dependency directory before the working-directory copy when multiple native builds coexist.
* **Verification**: A real CUDA `parallel-cuda-2` run over all 8 chunks and both ASR windows loaded two `CUDA0` engines, completed 96 measured calls with zero failures or baseline mismatches, retained 6.47 GiB minimum free VRAM, and measured 1.22x speedup. Its 1.60x parallel p95 latency ratio kept the full workload gate-ineligible, so production ASR remains serialized; the report is `artifacts/asr-concurrency-parallel-cuda-2-full-20260816/asr-concurrency-report.json`.

## 2026-08-15

* **Fix**: Sentence-boundary retry pieces now share the original logical chunk's audio-token/sample budget. A retry part that reaches its allocation, lacks a boundary, fails, or would make the concatenated result reach the aggregate cap fails closed before persistence instead of silently accepting over-budget audio.
* **Persistence**: Generation records the exact per-chunk `maxAudioTokens` input as `audioCapTokens.<index>` in manifest metadata, and compatible resume/regeneration preserves additive cap evidence.
* **Instrumentation**: Bounded state-event traces now retain the newest observation, so long batches cannot evict their terminal `COMPLETED`/`FAILED` evidence and be misreported.
* **Validation**: Deterministic validation uses cleaned synthesis text and the persisted original-chunk cap; invalid metadata fails closed, while legacy manifests report an explicit unavailable-cap finding rather than inventing an estimate.
* **Verification**: Full desktop tests and a three-chunk fake headless agent round pass. A fresh native CUDA/Vivian round persisted two chunks and rejected the third at the new aggregate guard, proving fail-closed behavior; automatic smaller replanning and a green full native batch remain separate work.
* **Implementation**: Added fresh-batch adaptive audio replanning. Measured text/context budgets keep 569 context tokens of headroom and target a conservative 384-token duration budget; lossless splits rebalance tiny tails and merge short adjacent pieces only when the combined request remains below the native 512-token floor. Existing manifests, chunk artifacts, selected indexes, and mixed voice maps are never renumbered.
* **Verification**: A fresh instrumented CUDA/Vivian/ASR run changed three source chunks into ten final chunks, generated all ten under the aggregate cap, passed deterministic and ASR validation, and produced `combined.wav`. The report and rendered proof cards show the final 10/10 completion contract and action sequence.

## 2026-08-14

* **Instrumentation**: Added a headless `--agent-smoke` application entry point that drives the real Studio batch orchestration through generation, deterministic validation, WAV recombination, and state observation. Fake mode uses a deterministic `BatchEngine`; native mode requires one reusable native session and rejects CLI fallback.
* **Evidence**: The runner writes `agent-report.json` with engine call counts, normalized generated texts, state transitions, validation findings, and artifact paths. `AgentSmokeRunnerTest` and a CLI fake-mode run pass; native model/JNI and audio playback evidence remain opt-in acceptance checks.
* **UI instrumentation**: Added `StudioUiInstrumentation` so the agent drives the Batch screen's generate, load-manifest, validate-all, and combine callback sequence through code, with before/after UI-state snapshots in the report. No window, pixel capture, or OS input is part of the agent loop.
* **Operations**: Documented the agent loop and corrected the configured desktop test command to `:composeApp:desktopTest`.
* **Adversarial hardening**: Batch validation now fails closed for `PENDING`, `FAILED`, or `CANCELLED` chunks, and persistence/state projection cannot record a passing validation for an incomplete chunk.
* **UI instrumentation**: Extended the code-level Batch hand with resume, regenerate-all, per-chunk regeneration and validation, and cancellation. Action evidence now records `completed: false` with state and failed-chunk details when a domain postcondition fails.
* **Verification**: Added deterministic desktop coverage for the extended action round trip, failed validation reporting, and cancellation persistence; regeneration tests explicitly revalidate after stale validation is cleared.
* **Agent runner hardening**: Native sentence-boundary audio-budget retries no longer look like a failed one-call-per-chunk run; the completion predicate now trusts durable manifest chunks while still requiring successful engine calls and completed UI actions.
* **Instrumentation**: Added explicit native runner controls for named speaker, instruction, bounded chunk plans, flat output, and transparent ASR skipping; report schema now records the requested voice/prompt and ASR scope.
* **Verification**: A fresh headless CUDA run from `D:\t1\t1.txt` generated three 1,600-character chunks with the CustomVoice model and Vivian, persisted all three with deterministic validation PASS, recombined a 24 kHz mono WAV, and rendered report/manifest evidence cards. A separate ASR-enabled attempt remains archived because one probabilistic edge score failed closed.

## 2026-08-13

* **Fix**: Batch audio-token budgeting is now voice- and context-aware. The budget subtracts the measured formatted-text and instruction tokens from the native 4,096-token talker context, while CustomVoice retains the larger output allowance. The same computed limit is passed to streaming and buffered JNI synthesis, causing oversized named-speaker chunks to retry at sentence boundaries instead of clipping their suffix.
* **Fix**: Batch chunk seeking now uses the local playback write cursor after an audio-device flush, and per-chunk regeneration now prioritizes the manifest's persisted model, prompt, and voice identity instead of current Studio defaults.
* **Fix**: Batch manifest loading and refresh now rebuild the replay request, per-chunk voice parameters, batch defaults, row validation state, and result snapshot from the external manifest; Batch screen lifecycle polling observes atomic manifest writes from generation, validation, or another process.
* **Fix**: Loaded CustomVoice manifests now provide their persisted speaker directly to every batch request path, include that voice in row options before capability probing completes, and probe manifest-selected models automatically after load.
* **Fix**: Batch UI state now reloads the durable manifest after generation and validation, updating the active manifest, completed result snapshot, and signature-valid validation indicators together across lifecycle boundaries.
* **Persistence**: Batch validation results now store a per-chunk SHA-256 signature over model, voice prompt, voice, and transcript. Loaded results are ignored when that signature is missing or no longer matches the chunk inputs.

## 2026-08-12

* **Fix**: Batch generation now retries long chunks at 95% of their safe audio budget, not only after reaching the exact ceiling. Native output can stop slightly below the ceiling while still clipping the final sentence; sentence-boundary recombination now catches that case before persistence.
* **Fix**: ASR edge validation now accepts a naturally completed five-second prefix even when it contains fewer than the 160-character comparison target and has minor spelling/article differences from the transcript. It still requires a contiguous four-word edge match, keeping unrelated speech below the threshold.
* **Fix**: Batch generation now queries named-speaker capabilities after loading each selected model, uses the first available model speaker when a request omitted one, and fails before native synthesis with an actionable message when CustomVoice has no resolvable voice input.

## 2026-08-11

* **Fix**: Batch CustomVoice requests now carry the selected named speaker even when the row display name is `Default Voice`; loaded manifests also preserve their persisted speaker identity instead of sending a speaker-less request to native synthesis.
* **Fix**: Deterministic batch validation now applies the synthesis whitespace cleanup when comparing manifest, sidecar, and source text, preventing harmless extra spaces, trimmed edges, and normalized line breaks from producing false failures while preserving lossless persisted text and exact resume fingerprints.
* **UX**: Loaded manifests now expose an explicit `Resume batch` operation that preserves completed chunks and continues generation from the next unfinished or invalid chunk without regenerating completed audio.
* **Fix**: Batch planning now reserves 12.5% below the native audio-token ceiling, limits new chunks to a calibrated 4,000 characters, and validates WAV sample frames against token-expanded sample capacity rather than comparing incompatible units. Existing manifests above the safe limit must be regenerated or explicitly split before replay.
* **Implementation**: Exposed the native Qwen BPE tokenizer count through the existing JNI/DLL boundary and used it for measured input chunking at a 2,048-token budget. Added sentence-boundary audio-budget retry that regenerates over two smaller text spans and concatenates the audio before persistence when the safe audio budget is reached.
* **Implementation**: Added a synthesis-only whitespace cleanup pass that collapses repeated spaces/tabs, reduces newline runs, and trims chunk edges while keeping manifest text and sidecars lossless for validation and replay.
* **Persistence**: Stored per-chunk validation pass/fail state and review message in `manifest.json`; loading restores those indicators, while regenerated or reconfigured chunks clear stale validation state.
* **UX**: Pipelined batch progress now advances on generated audio as well as durable persistence; the UI shows separate generated and complete counts so CPU-side overlap cannot appear stalled.
* **Fix**: Background WAV persistence now immediately commits the matching `COMPLETE` chunk to `manifest.json` and publishes that snapshot to the batch UI; manifest progress no longer waits for the next GPU generation to finish.
* **Fix**: Completed-chunk persistence now merges with the latest manifest under the store lock, preventing a background writer from overwriting newer chunk progress.
* **Diagnostics**: Batch persistence now logs writer start, completion, exact WAV/manifest paths, and failures separately from native synthesis timing.
* **Diagnostics**: Batch alignment and persistence diagnostics now include ISO-8601 local timestamps so inference, writing, and manifest commits can be compared chronologically.
* **Fix**: Generate Manifest now persists the selected batch voice, model, and prompt as defaults on each new chunk while retaining explicit per-chunk overrides during rescans.
* **Fix**: Selecting a batch source file or output directory no longer clears the configured batch voice, model, or prompt before manifest generation.

## 2026-08-10

* **UX**: Per-chunk generation now keeps the row action in place and changes it to an hourglass `Generating…` state for the active chunk, matching validation feedback instead of removing/reflowing row controls.
* **UX**: Batch rows now place the M/P configuration control before the voice selector and shorten the display-only `Default Voice (Model)` label to `Default Voice` without changing persisted voice identity.
* **UX**: Validation detail popovers now show only failed findings and present ASR failures as explicit Expected versus Actual values; passing prefix/suffix checks are omitted from the failure review.
* **Setup**: Added the Qwen3 Forced Aligner 0.6B F16 download as a separate model option using the exact `qwen3-forcedaligner-0.6b-f16.gguf` filename and Jaffe2718 GGUF source required by the bundled `qwen3-asr.cpp` tests. Source markers enable replacement of stale/incompatible files.
* **Implementation**: Batch playback now uses native TTS streaming text spans when available, persists checksum-guarded approximate alignment sidecars, rejects stale/non-monotonic alignment, labels missing estimates honestly, and reports device-clock/sidecar instrumentation. Playback position is derived from frames played by the audio device rather than frames queued.
* **UX**: Moved Batch operations directly above Batch parts and promoted batch workspace state into the shared view model so changing tabs retains the loaded manifest, replay request, selections, and progress state.
* **UX**: Added top-level Batch operations for validating all loaded chunks in one serialized deterministic/ASR pass and regenerating all chunks using their persisted per-chunk voice, model, and prompt settings.
* **UX**: Batch rows now show `SAVING` when a generated chunk is awaiting durable WAV/manifest persistence while the next chunk is being synthesized, making CPU-side pipeline progress visible before the chunk becomes `COMPLETE`.
* **Validation**: Deterministic batch validation now reports WAVs that are implausibly short for substantial chunk text, includes file size/duration evidence, and treats reaching the audio-token ceiling as a likely truncation error. Added regression coverage.

## 2026-08-09

* **Fix**: Batch manifest reload/resume now preserves each chunk's voice, model, and voice prompt while replacing audio/status metadata, including regeneration, failure, cancellation, and manifest rescans. Added a desktop regression test for write/reload/resume round-tripping.
* **Fix**: Batch validation presentation now renders every ASR prefix/suffix finding with chunk, expected text, transcript, score, pass/fail, and error details. The Batch screen also serializes validation against generation/recombination through screen-local job state, preventing validation reads from racing active batch file writes while preserving view-model ownership of workflow work.
* **Documentation**: Refreshed native-boundary concepts to reflect the populated TTS/ASR source submodules and clarified that CPU persistence overlap remains distinct from validation actions.
* **UX**: Moved batch generation out of the Synthesis view into a dedicated Batch navigation tab. The batch workflow keeps its existing manifest, resume, regeneration, validation, and recombination state through the shared Studio view model.
* **UX**: ASR validation now resolves the standard Qwen3-ASR GGUF from the Setup-managed model directory; Batch no longer asks users to browse for an ASR model.

* **Implementation**: Replaced the external-process ASR validation adapter with an in-process Qwen3-ASR GGUF runtime. The ASR source is a pinned native submodule, links into the existing JNI DLL/GGML build, and is downloaded through the normal model-management UI. Prefix/suffix validation now converts and crops WAV samples before calling native JNI.
* **2026-08-09 — ASR model source**: Corrected Setup's ASR download from the CrispASR conversion to the Jaffe2718 GGUF conversion used by the bundled `qwen3-asr.cpp` tests. Downloads now record their source URL and replace stale files whose marker does not match, preventing an incompatible pre-existing ASR file from being silently reused.
* **2026-08-09 — Batch manifest table**: Loaded manifests now expose per-chunk playback and one validation action. The action combines deterministic checks with ASR when installed, and the row records running/pass/fail state instead of requiring separate whole-manifest validation buttons.
* **2026-08-09 — Batch playback controls**: Per-chunk playback now buffers decoded PCM for seekable playback and exposes a row-local position slider and Stop action.
* **2026-08-09 — Validation details**: Per-chunk validation explanations and ASR transcripts are now opened from the row's pass/fail icon instead of rendered as a separate details section below the table.
* **2026-08-09 — Per-chunk voices**: Batch manifests now persist a voice name on every chunk. The batch table can assign voices independently, changing an assignment invalidates that chunk, and replay generation resolves the selected voice's native speaker or conditioning artifact per chunk.
* **2026-08-09 — Explicit batch identity controls**: Batch now exposes model selection and an optional voice prompt. These values are carried into the batch identity metadata and restored when a manifest is loaded.
* **2026-08-09 — Chunk review controls**: Each completed chunk now has quick prefix and suffix playback actions, and a text popover for reading the complete chunk without widening the table.
* **2026-08-09 — Batch progress placement**: Overall batch operation status is now shown beside the top-level progress, while the active chunk row shows its own generation indicator.
* **2026-08-09 — In-flight generation UX**: Generation now exposes the same explicit inline busy notice used by validation, explaining why batch edits and file actions are temporarily paused.
* **2026-08-09 — Per-chunk model and prompt**: Model selection and voice prompt moved into each chunk's configuration popover. Manifest chunks now persist `modelName` and `voicePrompt`; generation reloads the native session when consecutive chunks select different models.
* **2026-08-09 — Model-specific default voices**: Batch now probes a row's selected model for named speakers using the same native capability path as Studio, so CustomVoice defaults appear even when the Studio-active model is a different variant.
* **2026-08-09 — Chunk playback highlighting**: The chunk text popover now highlights playback progress for full, prefix, and suffix playback using the existing batch audio position state.
* **Verification**: Kotlin desktop tests pass. Native CPU configuration compiled the ASR sources and linked `qwen3_tts.dll`; copying the rebuilt DLL was deferred because the repository-root DLL was locked by a running application.

## 2026-08-08

* **Fix**: Made text chunking lossless after line-ending normalization. The previous implementation trimmed paragraphs, collapsed blank-line separators, and reconstructed oversized paragraphs from whitespace-delimited words, which could drop source characters. The `D:\t1.txt` regression round-trip now verifies 20,397 normalized characters across six chunks with no loss.
* **UX**: Added explicit batch resume status so the UI reports when it is scanning, how many compatible chunks were resumed, or that generation is starting from chunk 0.
* **Fix**: Resume no longer depends on the transient timestamp-based UI batch ID. A compatible manifest in the selected output directory can resume across app restarts while model, voice, chunk, and text fingerprints remain enforced.
* **Implementation**: Added a bounded one-slot CPU persistence pipeline so PCM encoding and atomic chunk writes overlap the next GPU generation while ordered manifest commits and memory bounds remain intact. Rebuilt the CUDA JNI DLL successfully.
* **Implementation**: Made batch sizing adaptive to the active backend's reported free memory and available system memory, with a per-chunk audio-token ceiling to prevent long 5,000-character requests from filling the native audio limit. Sampling temperature remains unchanged; resume fingerprints include the effective adaptive chunking policy.
* **Implementation**: Added resumable batch execution with text/model fingerprints and validated chunk-file scanning; corrupt or missing chunks are regenerated while intact chunks are skipped. Recombination now avoids an all-batch PCM allocation by streaming validated chunks to the output WAV.

* **Behavior change**: Batch packing now combines complete paragraphs up to the 5,000-character limit while preserving paragraph separators; only paragraphs that individually exceed the limit are split.

* **UX**: Batch generation now shows elapsed time and an approximate remaining-time estimate based on completed chunks.

* **Implementation**: Batch text input now greedily fills 5,000-character requests within each blank-line-delimited paragraph, splitting at whitespace and safely handling overlong words; it no longer synthesizes one request per source line.

@@
## 2026-08-03

* **Implementation**: Wired the official VoiceDesign → Base clone-prompt bridge into Studio. Accepted previews now retain reference text separately from style instructions; save uses the existing full ICL extraction/persistence path, requires a matching Base model, and switches later synthesis to Base + ICL mode. Compile and desktop tests pass; real-model prompt extraction remains pending.

* **Plan**: Added `STORY-VOICE-REUSE-003` (`M-VOICE-REUSE-003`, Wave 3) for the complete Qwen VoiceDesign → Base clone-prompt workflow. Repository and upstream inspection confirm that native ICL prompt primitives already exist; Studio is missing the two-stage model transition, full-prompt persistence, and batch wiring.

* **UX clarification**: Kept the `Keep voice` affordance visible after a preview even when the selected model cannot provide reusable speaker embeddings; it is now disabled with an explanation instead of disappearing silently.

* **Window UX**: Increased the default desktop height to 1000dp and added a density-aware 1200x900dp minimum window size so Studio controls cannot be compressed into a collapsed layout during startup or resize.

* **Capability fix**: Hid `Keep voice` for instruction-capable models that do not report speaker-cloning support, such as the observed CustomVoice path without a reusable speaker encoder. Capture now fails early with an actionable capability message instead of surfacing the native lazy-encoder internal error.

* **Bug fix**: `Keep voice` now extracts from the engine session that generated the accepted preview instead of reloading the talker model. This preserves the native TTS model path required by lazy encoder loading and avoids a redundant release/reload cycle.

* **UX fix**: Kept Streaming, Read Aloud, and captured-voice actions in one compact action row. Removed the full-width weighted capture row that caused the `Keep voice` button to jump to the far edge. Kotlin compile and desktop tests pass.

* **Implementation/evidence**: Promoted the captured Read Aloud voice into the existing named `VoicePreset` lifecycle. Studio now accepts a name, copies the preview WAV into managed recordings, extracts/persists the embedding, auto-selects the new preset, and reloads it through the existing preset loader. Kotlin compile, desktop tests, and portable packaging pass; real-model GUI save/restart/reload acceptance remains open.

* **Plan**: Added `STORY-VOICE-REUSE-002` (`M-VOICE-REUSE-002`, Wave 3) to name, persist, reload, and batch-reuse an accepted instruction-driven Read Aloud/Streaming voice. The story depends on the existing temporary capture path and preserves compatibility/provenance and cleanup acceptance boundaries.

* **UX hardening**: Moved the `Keep voice` affordance into the primary Read Aloud card so it remains visible at compact window sizes instead of living only in a separately scrollable section.

* **Implementation**: Added `Keep voice` capture for instruction-driven Read Aloud/Streaming previews. The accepted preview is converted to a temporary reusable speaker embedding; batch generation uses that artifact for every chunk and records the source instruction as manifest provenance without redesigning the voice per chunk. Kotlin compile and desktop tests pass; portable real-model acceptance remains pending.

* **Plan**: Started `STORY-VOICE-REUSE-001` (`M-VOICE-REUSE-001`, Wave 3) to capture an accepted instruction-driven Read Aloud/Streaming voice and reuse one fixed speaker artifact across batch chunks.

* **Verification**: Completed `STORY-BATCH-UI-001` with the portable UI and the configured D: model. The two-line fixture produced `manifest.json`, two complete 24 kHz WAV chunks, and a verified combined WAV under `D:\qwen-tts-studio\batch-smoke-output`; the manifest preserves source text and checksums.

* **Plan**: Started `STORY-BATCH-UI-001` (`M-BATCH-UI-001`, Wave 2) to expose the reusable batch engine from the portable Studio UI and instrument progress, manifest provenance, cancellation, and recombination.
* **Implementation**: Added portable Studio batch controls for UTF-8 line-oriented text input, output-directory selection, progress/cancel/error status, and WAV recombination; persisted source text in manifest chunks and added progress/cancellation tests. Compile and desktop tests pass.
* **Verification**: Rebuilt the CPU portable ZIP after the batch UI integration. A real-model two-line portable batch smoke test remains pending before `STORY-BATCH-UI-001` can close.
* **Instrumentation**: Added a `BatchEngine` seam and desktop fake-engine test proving one load plus ordered multi-chunk generation; the packaged portable artifact was rebuilt with the final batch UI and instrumentation. No GGUF model is present in this environment for real inference verification.
* **Verification**: The packaged portable executable passed an 8-second startup smoke check and was stopped cleanly; real batch output remains unverified because no model is available locally.
* **Boundary**: Rechecked the configured model directory and common user download locations; no GGUF model or batch output is present, so the remaining acceptance item requires external model state and manual portable UI interaction.
* **Instrumentation**: Added `scripts/smoke-batch-portable.ps1` to create an isolated UTF-8 two-line fixture, validate the portable executable/model prerequisite, and optionally launch the UI; it exits distinctly when no GGUF model is available.
* **Hardening**: Completed `TASK-BATCH-UI-001E`: serialized native teardown, fixed batch-directory ownership during recombination, separated recombination state, recorded partial failures, protected chunk files, and tested final-cancel semantics. Desktop tests and a fresh portable startup smoke check pass.
* **Documentation**: Added the portable batch smoke-testing operation concept with explicit evidence levels and prerequisite handling.
* **Plan**: Started `STORY-VOICE-004` (`M-VOICE-002`, Wave 2) to execute and verify a local Windows CPU artifact build, with MSI treated as a separate prerequisite-sensitive path.
* **Verification**: Completed `STORY-VOICE-004`: local CPU native build and Compose packaging passed in 152 seconds; app image and portable ZIP passed structural payload checks; MSI remained unverified due to absent WiX and the delegated inspection did not launch the GUI.
* **Verification**: User tested the portable artifact successfully and confirmed it reused the existing model download without redownloading; `EPIC-VOICE-001` is closed as tested, with MSI and fresh-machine portability remaining explicit limits.
* **Investigation**: Completed Wave 1 Stories for Windows build/output caching and Studio voice construction; documented the proposed reusable batched-generation boundary and its native/audio-format blockers.
* **Implementation**: Completed `EPIC-VOICE-001` with a native-only buffered batch session, atomic WAV/manifest output, fail-closed recombination, Studio integration, and a passing 15-test desktop suite. CLI fallback and streaming remain explicitly outside the batch guarantee.
* **Creation**: Replaced the single non-conformant orientation document with an OKF v0.2 bundle structure.
* **Update**: Codified the single-orchestrator operating model and EPIC/Story/Task/Milestone(Wave) planning hierarchy in the agent-orchestration skill and workflow concepts.
* **Plan**: Started `EPIC-VOICE-001` for Wave 1 investigation of Windows builds/output caching, Studio voice construction, and reusable batched generation; linked Stories and Tasks are recorded under `docs/okf/planning/`.
* **Investigation**: Started parallel repository investigations for architecture, runtime, product workflows, and operations.
* **Update**: Added linked architecture, runtime, workflow, and operations concept families produced from repository inspection.
* **Update**: Added family indexes and corrected the root progressive-disclosure links.
* **Update**: Added a first-class reference concept for Google’s OKF v0.2 specification and added Python document/bundle conformance tests to the local skill.

* **Implementation**: CustomVoice batch generation now visibly reuses the selected named speaker and current instruction across all chunks, without requiring a cloning embedding or a captured preview. The disabled clone-only affordance is no longer shown for named-speaker models.
* **UX/Diagnostics**: Batch output now includes UTF-8 `chunk-XXXXXX.txt` sidecars beside each WAV. Existing valid chunks repair their sidecars from the manifest during resume, making text/audio boundaries directly inspectable.
* **Fix**: Increased the adaptive per-chunk audio-token safety margin after observing speech truncation at a chunk boundary. Text chunk sizes remain memory-limited; the output ceiling now allows substantially more natural speech expansion before reaching the native maximum.
* **Implementation**: Made `manifest.json` replayable. It now persists source text for every pending/complete chunk, stores the instruction needed to reconstruct the request, loads through a manifest picker, continues missing chunks, and exposes the existing output directory for recombination.
* **Implementation**: Added selective chunk regeneration from a loaded manifest. Users can enter zero-based indexes or ranges such as `0, 3-5`; only those chunks are synthesized again and the full batch can then be recombined.
* **UX**: Added `Generate manifest` recovery action. It scans contiguous `chunk-XXXXXX.txt` sidecars and matching WAVs, rebuilds completion/checksum metadata without changing audio, and loads the result for continuation or selective regeneration.
* **UX**: Loaded/generated manifests now render as an indexed batch worklist. Each part can be generated or regenerated independently, and a Combine action is available once every part is complete.
* **UX**: Selecting a source text file now enables Generate manifest. The action creates source chunk sidecars and a replayable manifest in the selected output directory, associating any matching existing WAVs.
* **Fix**: Per-chunk Generate/Regenerate actions now use single-target execution and return control after that chunk. Other pending chunks remain pending for later selection or batch continuation.
* **Implementation**: Added deterministic batch validation for text conservation, manifest/sidecar agreement, WAV integrity, completion state, and possible audio-token-cap truncation. Added the probabilistic Qwen-ASR adapter contract for prefix/suffix similarity without claiming ASR support is installed.
* **Implementation**: Added an external-process Qwen ASR adapter with bounded execution and `{wav}`/`{window}` placeholders, allowing prefix/suffix probabilistic checks to run against an installed ASR wrapper without embedding a second inference runtime.
* **Refactor**: Moved all batch-generation presentation into the dedicated `BatchScreen`; `StudioScreen` now contains synthesis-only controls while the shared `StudioViewModel` preserves batch state across tabs.
* **UX**: Moved batch progress, elapsed-time estimate, operation status, and the generation lock notice immediately above Batch operations and Batch parts so progress remains adjacent to the worklist.
* **UX**: Added whole-batch model/prompt and voice defaults, with explicit Apply to all chunks behavior; new runs and manifests use the defaults while loaded manifests retain per-chunk overrides until applied.
* **Fix**: Batch voice-prompt fields are editable independently of the currently active Studio model capability, so prompts can be configured for a separately selected batch model and persisted into the manifest.
* **Verification/Fix**: The Chinese/Vivian headless profile now auto-selects a 40-character lossless target when no override is supplied. Punctuation-only fragments are attached to adjacent speech chunks. A fresh CUDA run generated and ASR-validated all 45 bounded chunks, produced `combined.wav`, and reported `BATCH_VERIFICATION_STATUS=passed` with the configured Qwen TTS/ASR and native-runtime fingerprints.
