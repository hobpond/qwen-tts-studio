package com.qwen.tts.studio.batch

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.qwen.tts.studio.engine.AsrAudioWindow
import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenAsrEngine

enum class ValidationSeverity { INFO, WARNING, ERROR }

data class ValidationFinding(
    val severity: ValidationSeverity,
    val code: String,
    val chunkIndex: Int? = null,
    val message: String
)

data class BatchValidationReport(
    val findings: List<ValidationFinding>,
    val asrFindings: List<AsrValidationFinding> = emptyList(),
    val asrMetrics: AsrValidationMetrics? = null,
    val asrBackendEvidence: AsrBackendEvidence? = null,
    val asrModelMetadata: Map<String, String> = emptyMap()
) {
    val errors: List<ValidationFinding> get() = findings.filter { it.severity == ValidationSeverity.ERROR }
    val warnings: List<ValidationFinding> get() = findings.filter { it.severity == ValidationSeverity.WARNING }
    val passed: Boolean get() = errors.isEmpty()
}

/**
 * The exact aggregate audio budget used for one original batch chunk.
 *
 * Sentence-boundary retries may generate multiple native parts and concatenate
 * them into one persisted WAV. The validator therefore compares the aggregate
 * WAV against the original chunk budget, not against an independently inferred
 * budget for each retry part.
 */
data class BatchValidationCapInput(val maxAudioTokens: Int) {
    init {
        require(maxAudioTokens > 0) { "maxAudioTokens must be positive" }
    }
}

/**
 * Minimal manifest metadata needed for exact aggregate-cap validation.
 *
 * The generator persists the final per-chunk `maxAudioTokens` value under this
 * key before queuing the WAV write. Legacy manifests may not have the
 * additive metadata; validation reports `AUDIO_CAP_INPUTS_UNAVAILABLE` for
 * those manifests and does not invent a replacement estimate. The key's value
 * is the budget passed for the original chunk before any sentence-boundary
 * retry.
 */
object BatchValidationMetadata {
    const val AUDIO_CAP_TOKENS_PREFIX = "audioCapTokens."

    fun audioCapTokensKey(chunkIndex: Int): String = "$AUDIO_CAP_TOKENS_PREFIX$chunkIndex"
}

object BatchValidator {
    fun validate(
        store: BatchAudioStore,
        manifest: BatchManifest,
        source: String? = null,
        capInputs: Map<Int, BatchValidationCapInput> = emptyMap(),
        chunkIndices: Set<Int>? = null
    ): BatchValidationReport {
        val findings = mutableListOf<ValidationFinding>()
        val ordered = manifest.chunks.sortedBy { it.index }
        val selected = chunkIndices?.let { requested ->
            val available = ordered.map { it.index }.toSet()
            (requested - available).sorted().forEach { missing ->
                findings += ValidationFinding(
                    ValidationSeverity.ERROR,
                    "CHUNK_MISSING",
                    missing,
                    "Requested validation chunk $missing is not present in the manifest."
                )
            }
            ordered.filter { it.index in requested }
        } ?: ordered
        if (chunkIndices == null && ordered.map { it.index } != (0 until manifest.expectedChunkCount).toList()) {
            findings += ValidationFinding(ValidationSeverity.ERROR, "INDEX_GAP", message = "Manifest chunk indexes are not contiguous.")
        }

        val sidecarText = buildString {
            selected.forEach { chunk ->
                val sidecar = store.directoryPath.resolve("chunk-%06d.txt".format(chunk.index))
                if (!Files.isRegularFile(sidecar)) {
                    findings += ValidationFinding(ValidationSeverity.ERROR, "TEXT_SIDECAR_MISSING", chunk.index, "Text sidecar is missing.")
                } else {
                    val text = Files.readString(sidecar, StandardCharsets.UTF_8)
                    if (normalizeForValidation(text) != normalizeForValidation(chunk.text)) {
                        findings += ValidationFinding(ValidationSeverity.ERROR, "TEXT_MANIFEST_MISMATCH", chunk.index, "Text sidecar differs from manifest text after synthesis normalization.")
                    }
                    append(normalizeLineEndings(text))
                }
                if (chunk.status != BatchChunkStatus.COMPLETE) {
                    findings += ValidationFinding(
                        ValidationSeverity.ERROR,
                        "CHUNK_NOT_COMPLETE",
                        chunk.index,
                        incompleteChunkMessage(chunk)
                    )
                } else {
                    runCatching { store.validateChunkFile(chunk) }.onFailure {
                        findings += ValidationFinding(ValidationSeverity.ERROR, "WAV_INVALID", chunk.index, it.message ?: "WAV validation failed.")
                    }.onSuccess { audio ->
                        val synthesisText = TextBatching.cleanForSynthesis(chunk.text)
                        when (val cap = resolveCapInput(manifest, chunk, capInputs)) {
                            is CapInputResolution.Available -> reportAudioCap(findings, chunk, audio, synthesisText, cap)
                            is CapInputResolution.Missing -> findings += ValidationFinding(
                                ValidationSeverity.WARNING,
                                "AUDIO_CAP_INPUTS_UNAVAILABLE",
                                chunk.index,
                                "Exact aggregate audio-cap validation was not run because manifest metadata '${cap.key}' is absent. Persist the original per-chunk maxAudioTokens value under that key; no replacement estimate was used."
                            )
                            is CapInputResolution.Invalid -> findings += ValidationFinding(
                                ValidationSeverity.ERROR,
                                "AUDIO_CAP_INPUTS_INVALID",
                                chunk.index,
                                cap.message
                            )
                        }
                        val minimumFrames = minimumExpectedFrames(synthesisText, audio.sampleRate)
                        if (synthesisText.length >= MINIMUM_TEXT_CHARACTERS_FOR_DURATION_CHECK && audio.frameCount < minimumFrames) {
                            findings += ValidationFinding(
                                ValidationSeverity.ERROR,
                                "AUDIO_TOO_SHORT",
                                chunk.index,
                                "WAV is too short for the cleaned synthesis text: ${formatDuration(audio)} / ${audio.fileBytes} bytes, expected at least ${formatFrames(minimumFrames, audio.sampleRate)} for ${synthesisText.length} characters."
                            )
                        }
                    }
                }
            }
        }
        if (chunkIndices == null) source?.let {
            if (normalizeForValidation(sidecarText) != normalizeForValidation(it)) {
                findings += ValidationFinding(ValidationSeverity.ERROR, "SOURCE_ROUND_TRIP_FAILED", message = "Concatenated chunk text does not equal the source text after synthesis normalization.")
            }
        }
        if (findings.none { it.severity == ValidationSeverity.ERROR } && findings.none { it.code == "VALID" }) {
            findings += ValidationFinding(ValidationSeverity.INFO, "VALID", message = "Deterministic validation passed.")
        }
        return BatchValidationReport(findings)
    }

    private fun resolveCapInput(
        manifest: BatchManifest,
        chunk: BatchChunk,
        capInputs: Map<Int, BatchValidationCapInput>
    ): CapInputResolution {
        capInputs[chunk.index]?.let { return CapInputResolution.Available(it, "caller") }
        val key = BatchValidationMetadata.audioCapTokensKey(chunk.index)
        val raw = manifest.metadata[key] ?: return CapInputResolution.Missing(key)
        val tokens = raw.trim().toIntOrNull()
            ?: return CapInputResolution.Invalid("Manifest metadata '$key' must be a positive integer maxAudioTokens; aggregate cap validation failed closed.")
        if (tokens <= 0) {
            return CapInputResolution.Invalid("Manifest metadata '$key' must be a positive integer maxAudioTokens; aggregate cap validation failed closed.")
        }
        return CapInputResolution.Available(BatchValidationCapInput(tokens), "manifest:$key")
    }

    private fun reportAudioCap(
        findings: MutableList<ValidationFinding>,
        chunk: BatchChunk,
        audio: ChunkAudioMetadata,
        synthesisText: String,
        cap: CapInputResolution.Available
    ) {
        val capSamples = cap.input.maxAudioTokens.toLong() * AUDIO_SAMPLES_PER_TOKEN
        if (audio.frameCount >= capSamples) {
            val relation = if (audio.frameCount > capSamples) "exceeded" else "reached"
            findings += ValidationFinding(
                ValidationSeverity.ERROR,
                "AUDIO_CAP_REACHED",
                chunk.index,
                "Audio $relation the aggregate safe audio budget (${cap.input.maxAudioTokens} tokens / $capSamples samples, source=${cap.source}); output may be truncated. Observed samples=${audio.frameCount}, cleaned synthesis characters=${synthesisText.length}, WAV size=${audio.fileBytes} bytes, duration=${formatDuration(audio)}."
            )
        }
        if (audio.frameCount > capSamples) {
            findings += ValidationFinding(
                ValidationSeverity.ERROR,
                "AUDIO_AGGREGATE_CAP_EXCEEDED",
                chunk.index,
                "Persisted aggregate audio exceeds the original per-chunk cap: observed samples=${audio.frameCount}, cap samples=$capSamples, cap tokens=${cap.input.maxAudioTokens}, source=${cap.source}."
            )
        }
    }

    private sealed class CapInputResolution {
        data class Available(val input: BatchValidationCapInput, val source: String) : CapInputResolution()
        data class Missing(val key: String) : CapInputResolution()
        data class Invalid(val message: String) : CapInputResolution()
    }

    /** Explains the required recovery action when validation sees an unfinished chunk. */
    fun incompleteChunkMessage(chunk: BatchChunk): String =
        "Chunk ${chunk.index} is ${chunk.status}; generate or resume it until it is COMPLETE before validating."

    /**
     * Conservative speech-duration floor used only to identify obvious loss.
     * At 32 ms per character, normal variation remains allowed while severe
     * truncation becomes a deterministic, reviewable failure.
     */
    private fun minimumExpectedFrames(text: String, sampleRate: Int): Long =
        (text.trim().length * MINIMUM_SECONDS_PER_CHARACTER * sampleRate).toLong().coerceAtLeast(sampleRate.toLong() * MINIMUM_SECONDS)

    private fun formatDuration(audio: ChunkAudioMetadata): String =
        "%.2fs".format(java.util.Locale.ROOT, audio.frameCount.toDouble() / audio.sampleRate)

    private fun formatFrames(frames: Long, sampleRate: Int): String =
        "%.2fs".format(java.util.Locale.ROOT, frames.toDouble() / sampleRate)

    private const val MINIMUM_SECONDS_PER_CHARACTER = 0.032
    private const val MINIMUM_SECONDS = 2
    private const val MINIMUM_TEXT_CHARACTERS_FOR_DURATION_CHECK = 100
    private const val AUDIO_SAMPLES_PER_TOKEN = 1_920L

    /** Normalize line endings for comparison without changing persisted text. */
    private fun normalizeLineEndings(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')

    /** Validation ignores incidental source whitespace cleanup; persisted text remains lossless. */
    private fun normalizeForValidation(value: String): String =
        TextBatching.cleanForSynthesis(normalizeLineEndings(value))
}

enum class AsrWindow { PREFIX, SUFFIX }

/** Backend evidence attached to one ASR validation invocation. */
enum class AsrBackend { CPU, GPU, UNKNOWN }

/** Native backend/placement evidence captured when the ASR model is loaded. */
data class AsrBackendEvidence(
    val backend: AsrBackend,
    val nativeName: String? = null,
    val gpuActive: Boolean? = null,
    val encoderWeightsOnGpu: Boolean? = null,
    val decoderWeightsOnGpu: Boolean? = null,
    val deviceFreeBytes: Long? = null,
    val deviceTotalBytes: Long? = null
)

/** Wall-clock time spent inside one transcriber call for one chunk/window pair. */
data class AsrValidationWindowMetrics(
    val chunkIndex: Int,
    val window: AsrWindow,
    val elapsedMs: Long,
    val backend: AsrBackend = AsrBackend.CPU
) {
    init {
        require(elapsedMs >= 0) { "elapsedMs must not be negative" }
    }

    val elapsedMillis: Long get() = elapsedMs
}

/** Aggregated ASR timing and backend evidence for one generated chunk. */
data class AsrValidationChunkMetrics(
    val chunkIndex: Int,
    val windows: List<AsrValidationWindowMetrics>
) {
    val elapsedMs: Long get() = windows.sumOf { it.elapsedMs }
    val elapsedMillis: Long get() = elapsedMs
    val backend: AsrBackend get() = aggregateAsrBackend(windows.map { it.backend })
}

/**
 * Structured ASR evidence that callers can consume without changing the
 * existing finding list or validation semantics.
 *
 * `perWindow` contains one entry for every attempted ASR window, including
 * failed transcriptions. `perChunk` and `totalElapsedMs` are derived from the
 * same entries, so retries or partial validation cannot silently lose timing.
 */
data class AsrValidationMetrics(
    val perWindow: List<AsrValidationWindowMetrics> = emptyList()
) {
    val windows: List<AsrValidationWindowMetrics> get() = perWindow
    val perChunk: Map<Int, AsrValidationChunkMetrics>
        get() = perWindow.groupBy { it.chunkIndex }.mapValues { (chunkIndex, windows) ->
            AsrValidationChunkMetrics(chunkIndex, windows)
        }
    val chunks: List<AsrValidationChunkMetrics> get() = perChunk.values.toList()
    val chunkElapsedMs: Map<Int, Long> get() = perChunk.mapValues { (_, chunk) -> chunk.elapsedMs }
    val totalElapsedMs: Long get() = perWindow.sumOf { it.elapsedMs }
    val elapsedMs: Long get() = totalElapsedMs
    val totalElapsedMillis: Long get() = totalElapsedMs
    val windowCount: Int get() = perWindow.size
    val chunkCount: Int get() = perChunk.size
    val backendEvidence: Set<AsrBackend> get() = perWindow.map { it.backend }.toSet()
    val backends: Set<AsrBackend> get() = backendEvidence
    val backend: AsrBackend get() = aggregateAsrBackend(backendEvidence)

    companion object {
        /** Builds metrics from findings for callers that retained the legacy list API. */
        fun fromFindings(findings: Iterable<AsrValidationFinding>): AsrValidationMetrics =
            AsrValidationMetrics(
                findings.mapNotNull { finding ->
                    finding.elapsedMs?.let { elapsedMs ->
                        AsrValidationWindowMetrics(finding.chunkIndex, finding.window, elapsedMs, finding.backend)
                    }
                }.toList()
            )
    }
}

private fun aggregateAsrBackend(backends: Iterable<AsrBackend>): AsrBackend {
    val evidence = backends.toSet()
    return if (evidence.size == 1) evidence.single() else AsrBackend.UNKNOWN
}

data class AsrWindowResult(
    val window: AsrWindow,
    val expectedText: String,
    val transcript: String,
    val similarity: Double
) {
    constructor(window: AsrWindow, transcript: String, similarity: Double) : this(
        window,
        "",
        transcript,
        similarity
    )

    val score: Double get() = similarity
}

/** Adapter seam for the in-process Qwen3-ASR runtime. */
fun interface BatchAsrTranscriber {
    fun transcribe(wav: Path, window: AsrWindow): String

    /** Existing lambda callers remain CPU-safe; richer adapters can override this evidence. */
    val backend: AsrBackend get() = AsrBackend.CPU
    val backendEvidence: AsrBackendEvidence? get() = null
    val modelMetadata: Map<String, String> get() = emptyMap()
}

/** Loads one GGUF model through the application's JNI DLL and reuses it for all windows. */
class NativeBatchAsrTranscriber(
    modelFile: java.io.File,
    private val threads: Int = 4,
    backendPreference: NativeBackendPreference = NativeBackendPreference.Cpu
) : BatchAsrTranscriber, AutoCloseable {
    private val engine = QwenAsrEngine(backendPreference).also { it.load(modelFile) }

    override val modelMetadata: Map<String, String> =
        BatchIdentity.auxiliaryArtifactMetadata("asrModelFile", modelFile)

    private val loadedBackendInfo = engine.backendInfo()

    override val backend: AsrBackend = loadedBackendInfo?.let { info ->
        when {
            info.gpuActive && info.weightsOnGpu -> AsrBackend.GPU
            info.name.equals("CPU", ignoreCase = true) -> AsrBackend.CPU
            else -> AsrBackend.UNKNOWN
        }
    } ?: AsrBackend.UNKNOWN

    override val backendEvidence: AsrBackendEvidence = AsrBackendEvidence(
        backend = backend,
        nativeName = loadedBackendInfo?.name,
        gpuActive = loadedBackendInfo?.gpuActive,
        encoderWeightsOnGpu = loadedBackendInfo?.encoderWeightsOnGpu,
        decoderWeightsOnGpu = loadedBackendInfo?.decoderWeightsOnGpu,
        deviceFreeBytes = loadedBackendInfo?.freeBytes,
        deviceTotalBytes = loadedBackendInfo?.totalBytes
    )

    override fun transcribe(wav: Path, window: AsrWindow): String = engine.transcribe(
        wav.toFile(),
        if (window == AsrWindow.PREFIX) AsrAudioWindow.PREFIX else AsrAudioWindow.SUFFIX,
        threads = threads
    ).text

    override fun close() = engine.close()
}

data class AsrValidationFinding(
    val chunkIndex: Int,
    val window: AsrWindow,
    val expectedText: String = "",
    val similarity: Double? = null,
    val transcript: String? = null,
    val error: String? = null,
    val elapsedMs: Long? = null,
    val backend: AsrBackend = AsrBackend.CPU
) {
    /** Review-facing name; `similarity` remains for compatibility with existing callers. */
    val score: Double? get() = similarity
    val passed: Boolean get() = error == null
    val elapsedMillis: Long? get() = elapsedMs

    constructor(
        chunkIndex: Int,
        window: AsrWindow,
        similarity: Double?,
        transcript: String?,
        error: String? = null
    ) : this(chunkIndex, window, "", similarity, transcript, error)
}

data class BatchAsrValidationResult(
    val findings: List<AsrValidationFinding>,
    val metrics: AsrValidationMetrics = AsrValidationMetrics.fromFindings(findings),
    val backendEvidence: AsrBackendEvidence? = null,
    val modelMetadata: Map<String, String> = emptyMap()
) {
    val passed: Boolean get() = findings.all { it.passed }
}

/** Result of validating one durable chunk before the next chunk is admitted. */
data class BatchChunkValidationResult(
    val chunkIndex: Int,
    val passed: Boolean,
    val message: String,
    val deterministic: BatchValidationReport,
    val asr: BatchAsrValidationResult? = null,
    /** True when the durable chunk changed after validation began. */
    val stale: Boolean = false
)

/**
 * Compare-and-swap identity for a validation result. ASR may run for a long
 * time, so the result must never be applied to a later regeneration or a
 * rechunked child that reused the same integer index.
 */
data class BatchChunkValidationLease(
    val index: Int,
    val fileName: String,
    val text: String,
    val displayIndex: String,
    val sampleRate: Int?,
    val frameCount: Long?,
    val sha256: String?,
    val generationSignature: String
) {
    fun matches(chunk: BatchChunk): Boolean =
        chunk.status == BatchChunkStatus.COMPLETE &&
            chunk.index == index &&
            chunk.fileName == fileName &&
            chunk.text == text &&
            chunk.displayIndex == displayIndex &&
            chunk.sampleRate == sampleRate &&
            chunk.frameCount == frameCount &&
            chunk.sha256 == sha256 &&
            BatchValidationSignature.forChunk(chunk) == generationSignature

    companion object {
        fun fromChunk(chunk: BatchChunk): BatchChunkValidationLease {
            require(chunk.status == BatchChunkStatus.COMPLETE) {
                "Chunk ${chunk.index} must be complete before validation can be queued."
            }
            return BatchChunkValidationLease(
                index = chunk.index,
                fileName = chunk.fileName,
                text = chunk.text,
                displayIndex = chunk.displayIndex,
                sampleRate = chunk.sampleRate,
                frameCount = chunk.frameCount,
                sha256 = chunk.sha256,
                generationSignature = BatchValidationSignature.forChunk(chunk)
            )
        }
    }
}

/** Validation seam used by the sequential generation/publish path. */
fun interface BatchChunkValidator {
    fun validate(manifest: BatchManifest, chunkIndex: Int): BatchChunkValidationResult
}

/** Keeps one ASR engine resident while validating one chunk at a time. */
class LoadedBatchChunkValidator(
    private val store: BatchAudioStore,
    private val transcriber: BatchAsrTranscriber? = null,
    private val minimumSimilarity: Double = 0.75
) : BatchChunkValidator {
    init {
        require(minimumSimilarity in 0.0..1.0) { "minimumSimilarity must be between 0 and 1" }
    }

    override fun validate(manifest: BatchManifest, chunkIndex: Int): BatchChunkValidationResult {
        val deterministic = BatchValidator.validate(
            store = store,
            manifest = manifest,
            source = null,
            chunkIndices = setOf(chunkIndex)
        )
        val chunk = manifest.chunks.firstOrNull { it.index == chunkIndex }
        val asr = if (transcriber != null && chunk?.status == BatchChunkStatus.COMPLETE) {
            BatchAsrValidator.validateWithMetrics(
                store = store,
                manifest = manifest,
                transcriber = transcriber,
                minimumSimilarity = minimumSimilarity,
                chunkIndices = setOf(chunkIndex)
            )
        } else null
        val deterministicPassed = deterministic.errors.none { it.chunkIndex == chunkIndex }
        val asrPassed = asr?.passed ?: true
        val passed = chunk != null && deterministicPassed && asrPassed
        val message = when {
            chunk == null -> "Chunk $chunkIndex is missing from the manifest."
            !deterministicPassed -> deterministic.errors.firstOrNull { it.chunkIndex == chunkIndex }?.message
                ?: "Deterministic validation failed."
            asr != null && !asrPassed -> asr.findings.firstOrNull { !it.passed }?.error
                ?: "ASR validation failed."
            asr == null -> "Deterministic validation passed. ASR model is not installed."
            else -> "Deterministic and ASR validation passed."
        }
        return BatchChunkValidationResult(chunkIndex, passed, message, deterministic, asr)
    }
}

/** Stable identity of the generated chunk inputs used by validation. */
object BatchValidationSignature {
    fun forChunk(chunk: BatchChunk): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(chunk.modelName, chunk.voicePrompt, chunk.voiceName, TextBatching.cleanForSynthesis(chunk.text)).forEach { value ->
            val bytes = (value ?: "").toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(chunk: BatchChunk): Boolean =
        chunk.validationSignature != null && chunk.validationSignature == forChunk(chunk)
}

object BatchAsrValidator {
    fun validate(
        store: BatchAudioStore,
        manifest: BatchManifest,
        transcriber: BatchAsrTranscriber,
        minimumSimilarity: Double = 0.75
    ): List<AsrValidationFinding> = validateWithMetrics(store, manifest, transcriber, minimumSimilarity).findings

    /**
     * Runs the legacy ASR validation and additionally returns timing/backend
     * evidence for every attempted chunk/window pair.
     */
    fun validateWithMetrics(
        store: BatchAudioStore,
        manifest: BatchManifest,
        transcriber: BatchAsrTranscriber,
        minimumSimilarity: Double = 0.75,
        chunkIndices: Set<Int>? = null
    ): BatchAsrValidationResult {
        val backend = transcriber.backend
        val findings = mutableListOf<AsrValidationFinding>()
        val timings = mutableListOf<AsrValidationWindowMetrics>()

        manifest.chunks
            .filter { it.status == BatchChunkStatus.COMPLETE }
            .filter { chunkIndices == null || it.index in chunkIndices }
            .forEach { chunk ->
            val synthesisText = TextBatching.cleanForSynthesis(chunk.text)
            AsrWindow.entries.forEach { window ->
                val expectedText = expectedSegment(synthesisText, window)
                var elapsedMs = 0L
                val finding = try {
                    val startedAt = System.nanoTime()
                    val transcript = try {
                        transcriber.transcribe(store.directoryPath.resolve(chunk.fileName), window)
                    } finally {
                        elapsedMs = elapsedMillisSince(startedAt)
                    }
                    val result = compare(synthesisText, transcript, window)
                    if (result.similarity >= minimumSimilarity) {
                        AsrValidationFinding(
                            chunkIndex = chunk.index,
                            window = window,
                            expectedText = result.expectedText,
                            similarity = result.similarity,
                            transcript = transcript,
                            elapsedMs = elapsedMs,
                            backend = backend
                        )
                    } else {
                        AsrValidationFinding(
                            chunkIndex = chunk.index,
                            window = window,
                            expectedText = result.expectedText,
                            similarity = result.similarity,
                            transcript = transcript,
                            error = "ASR similarity is below $minimumSimilarity",
                            elapsedMs = elapsedMs,
                            backend = backend
                        )
                    }
                } catch (error: Throwable) {
                    AsrValidationFinding(
                        chunkIndex = chunk.index,
                        window = window,
                        expectedText = expectedText,
                        error = error.message ?: "ASR validation failed",
                        elapsedMs = elapsedMs,
                        backend = backend
                    )
                }
                findings += finding
                timings += AsrValidationWindowMetrics(chunk.index, window, elapsedMs, backend)
            }
        }
        return BatchAsrValidationResult(
            findings,
            AsrValidationMetrics(timings),
            transcriber.backendEvidence,
            transcriber.modelMetadata
        )
    }

    private fun elapsedMillisSince(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAt).coerceAtLeast(0L))

    fun compare(expectedText: String, transcript: String, window: AsrWindow): AsrWindowResult {
        val synthesisText = TextBatching.cleanForSynthesis(expectedText)
        val target = expectedSegment(synthesisText, window)
        val actual = boundTranscript(normalize(transcript))
        val similarity = bestLocalSimilarity(target, actual, window)
        return AsrWindowResult(window, target, transcript, similarity)
    }

    fun expectedSegment(text: String, window: AsrWindow): String {
        val words = normalize(TextBatching.cleanForSynthesis(text)).split(WORD_SEPARATOR).filter(String::isNotBlank)
        if (words.isEmpty()) return ""
        val selected = when (window) {
            AsrWindow.PREFIX -> takeWithinLimit(words)
            AsrWindow.SUFFIX -> takeWithinLimit(words.asReversed()).asReversed()
        }
        return selected.joinToString(" ")
    }

    private fun takeWithinLimit(words: List<String>): List<String> {
        val selected = mutableListOf<String>()
        var length = 0
        for (word in words) {
            val nextLength = if (selected.isEmpty()) word.length else length + 1 + word.length
            if (nextLength > TARGET_CHARACTERS && selected.isNotEmpty()) break
            selected += word
            length = nextLength
            if (length >= TARGET_CHARACTERS) break
        }
        return selected
    }

    /**
     * Scores the best local transcript span. ASR windows can contain valid
     * speech immediately before or after the expected edge text, and a fixed
     * five-second crop can end before the whole expected segment is spoken.
     * Therefore PREFIX compares prefixes and SUFFIX compares suffixes of the
     * expected segment, while still requiring a meaningful contiguous edge
     * match. The ASR input is limited to five seconds, so the transcript may
     * contain fewer words than the 160-character target. Word substitutions
     * also use a small spelling tolerance because names and punctuation are
     * commonly transcribed slightly differently.
     */
    private fun bestLocalSimilarity(expected: String, actual: String, window: AsrWindow): Double {
        if (containsCjk(expected) || containsCjk(actual)) {
            return bestCjkLocalSimilarity(expected, actual, window)
        }
        val expectedWords = expected.split(WORD_SEPARATOR).filter(String::isNotBlank)
        val actualWords = actual.split(WORD_SEPARATOR).filter(String::isNotBlank)
        if (expectedWords.isEmpty()) return 1.0
        if (actualWords.isEmpty()) return 0.0

        // Do not derive this from the full target length: a five-second ASR
        // crop can end naturally after only a handful of words.
        val minimumEdgeWords = minOf(expectedWords.size, MINIMUM_EDGE_WORDS)
        var best = 0.0
        for (edgeLength in minimumEdgeWords..expectedWords.size) {
            val edge = when (window) {
                AsrWindow.PREFIX -> expectedWords.take(edgeLength)
                AsrWindow.SUFFIX -> expectedWords.takeLast(edgeLength)
            }
            val minimumCandidateWords = maxOf(1, kotlin.math.floor(edgeLength * MINIMUM_CANDIDATE_FRACTION).toInt())
            val maximumCandidateWords = minOf(
                actualWords.size,
                maxOf(minimumCandidateWords, kotlin.math.ceil(edgeLength * MAXIMUM_CANDIDATE_FRACTION).toInt())
            )
            for (start in actualWords.indices) {
                for (length in minimumCandidateWords..maximumCandidateWords) {
                    val end = start + length
                    if (end > actualWords.size) break
                    val candidate = actualWords.subList(start, end)
                    val distance = levenshteinDistance(edge, candidate)
                    val similarity = 1.0 - distance / maxOf(edge.size, candidate.size).toDouble()
                    if (similarity > best) best = similarity
                    if (best >= 1.0) return 1.0
                }
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    /**
     * Chinese ASR does not insert word spaces, and a five-second edge crop
     * commonly ends in the middle of a clause. Treating punctuation-delimited
     * Chinese clauses as words makes an accurate partial transcript look like
     * several missing words. Compare character spans at the requested edge
     * instead, while keeping the same local-window and similarity threshold.
     */
    private fun bestCjkLocalSimilarity(expected: String, actual: String, window: AsrWindow): Double {
        val expectedCharacters = normalizeCjk(expected)
        val actualCharacters = normalizeCjk(actual)
        if (expectedCharacters.isEmpty()) return 1.0
        if (actualCharacters.isEmpty()) return 0.0

        // Keep a meaningful minimum edge so a coincidental one- or two-character
        // overlap cannot validate an unrelated transcript. Short chunks are
        // compared in full because they may legitimately be shorter than this.
        val minimumEdgeCharacters = minOf(
            expectedCharacters.length,
            actualCharacters.length,
            MINIMUM_CJK_EDGE_CHARACTERS
        )
        var best = 0.0
        val maximumEdgeOffset = minOf(MAX_CJK_EDGE_DRIFT_CHARACTERS, expectedCharacters.length - minimumEdgeCharacters)
        for (edgeOffset in 0..maximumEdgeOffset) {
            val maximumEdgeLength = expectedCharacters.length - edgeOffset
            for (edgeLength in minimumEdgeCharacters..maximumEdgeLength) {
                val edgeStart = when (window) {
                    AsrWindow.PREFIX -> edgeOffset
                    AsrWindow.SUFFIX -> expectedCharacters.length - edgeLength - edgeOffset
                }
                if (edgeStart < 0) continue
                val edge = expectedCharacters.substring(edgeStart, edgeStart + edgeLength)
                val minimumCandidateCharacters = maxOf(
                    minimumEdgeCharacters,
                    kotlin.math.floor(edgeLength * MINIMUM_CANDIDATE_FRACTION).toInt()
                )
                val maximumCandidateCharacters = minOf(
                    actualCharacters.length,
                    maxOf(
                        minimumCandidateCharacters,
                        kotlin.math.ceil(edgeLength * MAXIMUM_CANDIDATE_FRACTION).toInt()
                    )
                )
                if (minimumCandidateCharacters > maximumCandidateCharacters) continue
                for (start in actualCharacters.indices) {
                    for (length in minimumCandidateCharacters..maximumCandidateCharacters) {
                        val end = start + length
                        if (end > actualCharacters.length) break
                        val candidate = actualCharacters.substring(start, end)
                        val distance = cjkCharacterLevenshtein(edge, candidate)
                        val similarity = 1.0 - distance / maxOf(edge.length, candidate.length).toDouble()
                        if (similarity > best) best = similarity
                        if (best >= 1.0) return 1.0
                    }
                }
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun normalizeCjk(value: String): String = normalize(value)
        .filterNot(Char::isWhitespace)
        .map { CJK_DIGIT_NORMALIZATION[it] ?: it }
        .joinToString("")
        .replace("万", "0000")

    private fun containsCjk(value: String): Boolean = value.any { character ->
        character in '\u3400'..'\u4DBF' ||
            character in '\u4E00'..'\u9FFF' ||
            character in '\uF900'..'\uFAFF' ||
            character in '\u3040'..'\u30FF'
    }

    private fun boundTranscript(value: String): String {
        if (value.length <= MAX_TRANSCRIPT_CHARACTERS) return value
        val half = MAX_TRANSCRIPT_CHARACTERS / 2
        // Preserve both edges: PREFIX evidence is normally at the start and
        // SUFFIX evidence is normally at the end of a long transcript.
        return value.take(half) + " " + value.takeLast(half)
    }

    private fun levenshteinDistance(a: List<String>, b: List<String>): Double {
        var previous = DoubleArray(b.size + 1) { it.toDouble() }
        for (i in a.indices) {
            val current = DoubleArray(b.size + 1)
            current[0] = (i + 1).toDouble()
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + (1.0 - wordSimilarity(a[i], b[j]))
                )
            }
            previous = current
        }
        return previous[b.size]
    }

    private fun wordSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = characterLevenshtein(a, b)
        return (1.0 - distance.toDouble() / maxOf(a.length, b.length)).coerceIn(0.0, 1.0)
    }

    private fun characterLevenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1
                )
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun cjkCharacterLevenshtein(a: String, b: String): Double {
        var previous = DoubleArray(b.length + 1) { it.toDouble() }
        for (i in a.indices) {
            val current = DoubleArray(b.length + 1)
            current[0] = (i + 1).toDouble()
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1.0,
                    previous[j + 1] + 1.0,
                    previous[j] + if (a[i] == b[j]) 0.0 else CJK_SUBSTITUTION_COST
                )
            }
            previous = current
        }
        return previous[b.length]
    }

    private const val TARGET_CHARACTERS = 160
    private const val MAX_TRANSCRIPT_CHARACTERS = 2_000
    private const val MINIMUM_EDGE_WORDS = 4
    private const val MINIMUM_CJK_EDGE_CHARACTERS = 6
    private const val MAX_CJK_EDGE_DRIFT_CHARACTERS = 4
    private const val CJK_SUBSTITUTION_COST = 0.50
    private const val MINIMUM_CANDIDATE_FRACTION = 0.60
    private const val MAXIMUM_CANDIDATE_FRACTION = 1.45
    private val WORD_SEPARATOR = Regex("\\s+")
    private val CJK_DIGIT_NORMALIZATION = mapOf(
        '〇' to '0',
        '零' to '0',
        '一' to '1',
        '二' to '2',
        '三' to '3',
        '四' to '4',
        '五' to '5',
        '六' to '6',
        '七' to '7',
        '八' to '8',
        '九' to '9',
        '两' to '2'
    )
}
