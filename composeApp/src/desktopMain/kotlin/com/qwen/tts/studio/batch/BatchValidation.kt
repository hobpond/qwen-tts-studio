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
                    if (text != chunk.text) {
                        findings += ValidationFinding(ValidationSeverity.ERROR, "TEXT_MANIFEST_MISMATCH", chunk.index, "Text sidecar differs from manifest text.")
                    }
                    append(text)
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
            val normalized = it.replace("\r\n", "\n").replace('\r', '\n')
            if (sidecarText != normalized) {
                findings += ValidationFinding(ValidationSeverity.ERROR, "SOURCE_ROUND_TRIP_FAILED", message = "Concatenated chunk text does not equal the normalized source text.")
            }
        }
        if (findings.isEmpty()) findings += ValidationFinding(ValidationSeverity.INFO, "VALID", message = "Deterministic validation passed.")
        return BatchValidationReport(findings)
    }
}

enum class AsrWindow { PREFIX, SUFFIX }

data class AsrWindowResult(val window: AsrWindow, val transcript: String, val similarity: Double)

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
    val similarity: Double? = null,
    val transcript: String? = null,
    val error: String? = null
)

object BatchAsrValidator {
    fun validate(
        store: BatchAudioStore,
        manifest: BatchManifest,
        transcriber: BatchAsrTranscriber,
        minimumSimilarity: Double = 0.75
    ): List<AsrValidationFinding> = manifest.chunks.filter { it.status == BatchChunkStatus.COMPLETE }.flatMap { chunk ->
        AsrWindow.entries.map { window ->
            runCatching {
                val transcript = transcriber.transcribe(store.directoryPath.resolve(chunk.fileName), window)
                val result = compare(chunk.text, transcript, window)
                if (result.similarity >= minimumSimilarity) {
                    AsrValidationFinding(chunk.index, window, result.similarity, transcript)
                } else {
                    AsrValidationFinding(chunk.index, window, result.similarity, transcript,
                        "ASR similarity is below $minimumSimilarity")
                }
            }.getOrElse { AsrValidationFinding(chunk.index, window, error = it.message ?: "ASR validation failed") }
        }
    }

    fun compare(expectedText: String, transcript: String, window: AsrWindow): AsrWindowResult {
        val expected = normalize(expectedText)
        val actual = normalize(transcript)
        val target = when (window) {
            AsrWindow.PREFIX -> expected.take(160)
            AsrWindow.SUFFIX -> expected.takeLast(160)
        }
        val distance = levenshtein(target, actual.take(240))
        val similarity = if (target.isEmpty()) 1.0 else 1.0 - distance.toDouble() / target.length.coerceAtLeast(1)
        return AsrWindowResult(window, transcript, similarity.coerceIn(0.0, 1.0))
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = current
        }
        return previous[b.length]
    }
}
