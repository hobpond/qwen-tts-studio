package com.qwen.tts.studio.batch

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import com.qwen.tts.studio.engine.AsrAudioWindow
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
    val asrFindings: List<AsrValidationFinding> = emptyList()
) {
    val errors: List<ValidationFinding> get() = findings.filter { it.severity == ValidationSeverity.ERROR }
    val warnings: List<ValidationFinding> get() = findings.filter { it.severity == ValidationSeverity.WARNING }
    val passed: Boolean get() = errors.isEmpty()
}

object BatchValidator {
    fun validate(store: BatchAudioStore, manifest: BatchManifest, source: String? = null): BatchValidationReport {
        val findings = mutableListOf<ValidationFinding>()
        val ordered = manifest.chunks.sortedBy { it.index }
        if (ordered.map { it.index } != (0 until manifest.expectedChunkCount).toList()) {
            findings += ValidationFinding(ValidationSeverity.ERROR, "INDEX_GAP", message = "Manifest chunk indexes are not contiguous.")
        }

        val sidecarText = buildString {
            ordered.forEach { chunk ->
                val sidecar = store.directoryPath.resolve("chunk-%06d.txt".format(chunk.index))
                if (!Files.isRegularFile(sidecar)) {
                    findings += ValidationFinding(ValidationSeverity.ERROR, "TEXT_SIDECAR_MISSING", chunk.index, "Text sidecar is missing.")
                } else {
                    val text = Files.readString(sidecar, StandardCharsets.UTF_8)
                    if (normalizeLineEndings(text) != normalizeLineEndings(chunk.text)) {
                        findings += ValidationFinding(ValidationSeverity.ERROR, "TEXT_MANIFEST_MISMATCH", chunk.index, "Text sidecar differs from manifest text.")
                    }
                    append(normalizeLineEndings(text))
                }
                if (chunk.status != BatchChunkStatus.COMPLETE) {
                    findings += ValidationFinding(ValidationSeverity.WARNING, "CHUNK_NOT_COMPLETE", chunk.index, "Chunk status is ${chunk.status}.")
                } else {
                    runCatching { store.validateChunkFile(chunk) }.onFailure {
                        findings += ValidationFinding(ValidationSeverity.ERROR, "WAV_INVALID", chunk.index, it.message ?: "WAV validation failed.")
                    }.onSuccess { audio ->
                        val cap = BatchMemoryPolicy.maxAudioTokens(chunk.text)
                        if (audio.frameCount >= cap) {
                            findings += ValidationFinding(ValidationSeverity.WARNING, "AUDIO_CAP_REACHED", chunk.index, "Audio reached the estimated token ceiling ($cap frames); possible truncation.")
                        }
                    }
                }
            }
        }
        source?.let {
            if (sidecarText != normalizeLineEndings(it)) {
                findings += ValidationFinding(ValidationSeverity.ERROR, "SOURCE_ROUND_TRIP_FAILED", message = "Concatenated chunk text does not equal the normalized source text.")
            }
        }
        if (findings.isEmpty()) findings += ValidationFinding(ValidationSeverity.INFO, "VALID", message = "Deterministic validation passed.")
        return BatchValidationReport(findings)
    }

    /** Normalize line endings for comparison without changing persisted text. */
    private fun normalizeLineEndings(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')
}

enum class AsrWindow { PREFIX, SUFFIX }

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
}

/** Loads one GGUF model through the application's JNI DLL and reuses it for all windows. */
class NativeBatchAsrTranscriber(modelFile: java.io.File, private val threads: Int = 4) : BatchAsrTranscriber, AutoCloseable {
    private val engine = QwenAsrEngine().also { it.load(modelFile) }

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
    val error: String? = null
) {
    /** Review-facing name; `similarity` remains for compatibility with existing callers. */
    val score: Double? get() = similarity
    val passed: Boolean get() = error == null

    constructor(
        chunkIndex: Int,
        window: AsrWindow,
        similarity: Double?,
        transcript: String?,
        error: String? = null
    ) : this(chunkIndex, window, "", similarity, transcript, error)
}

object BatchAsrValidator {
    fun validate(
        store: BatchAudioStore,
        manifest: BatchManifest,
        transcriber: BatchAsrTranscriber,
        minimumSimilarity: Double = 0.75
    ): List<AsrValidationFinding> = manifest.chunks.filter { it.status == BatchChunkStatus.COMPLETE }.flatMap { chunk ->
        AsrWindow.entries.map { window ->
            val expectedText = expectedSegment(chunk.text, window)
            runCatching {
                val transcript = transcriber.transcribe(store.directoryPath.resolve(chunk.fileName), window)
                val result = compare(chunk.text, transcript, window)
                if (result.similarity >= minimumSimilarity) {
                    AsrValidationFinding(chunk.index, window, result.expectedText, result.similarity, transcript)
                } else {
                    AsrValidationFinding(
                        chunk.index,
                        window,
                        result.expectedText,
                        result.similarity,
                        transcript,
                        "ASR similarity is below $minimumSimilarity"
                    )
                }
            }.getOrElse {
                AsrValidationFinding(
                    chunk.index,
                    window,
                    expectedText,
                    error = it.message ?: "ASR validation failed"
                )
            }
        }
    }

    fun compare(expectedText: String, transcript: String, window: AsrWindow): AsrWindowResult {
        val target = expectedSegment(expectedText, window)
        val actual = boundTranscript(normalize(transcript))
        val similarity = bestLocalSimilarity(target, actual, window)
        return AsrWindowResult(window, target, transcript, similarity)
    }

    fun expectedSegment(text: String, window: AsrWindow): String {
        val words = normalize(text).split(WORD_SEPARATOR).filter(String::isNotBlank)
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
     * match. Unrelated transcript text cannot pass by matching one short word.
     */
    private fun bestLocalSimilarity(expected: String, actual: String, window: AsrWindow): Double {
        val expectedWords = expected.split(WORD_SEPARATOR).filter(String::isNotBlank)
        val actualWords = actual.split(WORD_SEPARATOR).filter(String::isNotBlank)
        if (expectedWords.isEmpty()) return 1.0
        if (actualWords.isEmpty()) return 0.0

        val minimumEdgeWords = minOf(
            expectedWords.size,
            maxOf(4, kotlin.math.ceil(expectedWords.size * MINIMUM_EDGE_FRACTION).toInt())
        )
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
                    val distance = levenshtein(edge, candidate)
                    val similarity = 1.0 - distance.toDouble() / maxOf(edge.size, candidate.size)
                    if (similarity > best) best = similarity
                    if (best >= 1.0) return 1.0
                }
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun boundTranscript(value: String): String {
        if (value.length <= MAX_TRANSCRIPT_CHARACTERS) return value
        val half = MAX_TRANSCRIPT_CHARACTERS / 2
        // Preserve both edges: PREFIX evidence is normally at the start and
        // SUFFIX evidence is normally at the end of a long transcript.
        return value.take(half) + " " + value.takeLast(half)
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1)
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
        return previous[b.size]
    }

    private const val TARGET_CHARACTERS = 160
    private const val MAX_TRANSCRIPT_CHARACTERS = 2_000
    private const val MINIMUM_EDGE_FRACTION = 0.35
    private const val MINIMUM_CANDIDATE_FRACTION = 0.60
    private const val MAXIMUM_CANDIDATE_FRACTION = 1.45
    private val WORD_SEPARATOR = Regex("\\s+")
}
