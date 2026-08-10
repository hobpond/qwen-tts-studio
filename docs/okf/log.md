# OKF Update Log

## 2026-08-09

* **Fix**: Batch validation presentation now renders every ASR prefix/suffix finding with chunk, expected text, transcript, score, pass/fail, and error details. The Batch screen also serializes validation against generation/recombination through screen-local job state, preventing validation reads from racing active batch file writes while preserving view-model ownership of workflow work.
* **Documentation**: Refreshed native-boundary concepts to reflect the populated TTS/ASR source submodules and clarified that CPU persistence overlap remains distinct from validation actions.
* **UX**: Moved batch generation out of the Synthesis view into a dedicated Batch navigation tab. The batch workflow keeps its existing manifest, resume, regeneration, validation, and recombination state through the shared Studio view model.
* **UX**: ASR validation now resolves the standard Qwen3-ASR GGUF from the Setup-managed model directory; Batch no longer asks users to browse for an ASR model.

* **Implementation**: Replaced the external-process ASR validation adapter with an in-process Qwen3-ASR GGUF runtime. The ASR source is a pinned native submodule, links into the existing JNI DLL/GGML build, and is downloaded through the normal model-management UI. Prefix/suffix validation now converts and crops WAV samples before calling native JNI.
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
