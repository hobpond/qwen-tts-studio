package com.qwen.tts.studio.batch

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** A text range associated with an audio interval. Native TTS currently marks these estimates. */
data class BatchAlignmentSpan(
    val startText: Int,
    val endText: Int,
    val startSeconds: Float,
    val endSeconds: Float,
    val confidence: Float
)

data class BatchChunkAlignment(
    val textSha256: String,
    val wavSha256: String,
    val sampleRate: Int,
    val durationSeconds: Float,
    val quality: String,
    val method: String,
    val spans: List<BatchAlignmentSpan>
)

internal object BatchAlignmentCodec {
    private const val VERSION = "version=1"

    fun encode(alignment: BatchChunkAlignment): String = buildString {
        appendLine(VERSION)
        appendLine("textSha256=${alignment.textSha256}")
        appendLine("wavSha256=${alignment.wavSha256}")
        appendLine("sampleRate=${alignment.sampleRate}")
        appendLine("durationSeconds=${alignment.durationSeconds}")
        appendLine("quality=${alignment.quality}")
        appendLine("method=${alignment.method}")
        alignment.spans.forEach { span ->
            appendLine("span=${span.startText},${span.endText},${span.startSeconds},${span.endSeconds},${span.confidence}")
        }
    }

    fun decode(value: String): BatchChunkAlignment {
        val fields = value.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("span=") }
            .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
            .toMap()
        require(fields["version"] == "1") { "Unsupported batch alignment version" }
        val spans = value.lineSequence().filter { it.startsWith("span=") }.map { line ->
            val parts = line.removePrefix("span=").split(',')
            require(parts.size == 5) { "Invalid batch alignment span" }
            BatchAlignmentSpan(parts[0].toInt(), parts[1].toInt(), parts[2].toFloat(), parts[3].toFloat(), parts[4].toFloat())
        }.toList()
        return BatchChunkAlignment(
            textSha256 = fields.getValue("textSha256"),
            wavSha256 = fields.getValue("wavSha256"),
            sampleRate = fields.getValue("sampleRate").toInt(),
            durationSeconds = fields.getValue("durationSeconds").toFloat(),
            quality = fields.getValue("quality"),
            method = fields.getValue("method"),
            spans = spans
        )
    }
}

internal fun alignmentPath(directory: Path, index: Int): Path =
    directory.resolve("chunk-%06d.alignment".format(index))

internal fun BatchChunkAlignment.isValidFor(text: String, wavSha256: String, durationSeconds: Float): Boolean {
    if (textSha256 != sha256Text(text) || this.wavSha256 != wavSha256) return false
    if (sampleRate <= 0 || durationSeconds <= 0f || kotlin.math.abs(this.durationSeconds - durationSeconds) > 0.25f) return false
    if (spans.isEmpty()) return false
    var previousEnd = 0f
    return spans.all { span ->
        val valid = span.startText in 0 until text.length &&
            span.endText in (span.startText + 1)..text.length &&
            span.startSeconds >= 0f && span.endSeconds >= span.startSeconds &&
            span.endSeconds <= durationSeconds + 0.25f && span.startSeconds >= previousEnd - 0.001f &&
            span.confidence in 0f..1f
        if (valid) previousEnd = span.endSeconds
        valid
    }
}

internal fun sha256Text(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun readValidAlignment(directory: Path, chunk: BatchChunk, durationSeconds: Float): BatchChunkAlignment? {
    val sha = chunk.sha256 ?: return null
    val path = alignmentPath(directory, chunk.index)
    if (!Files.isRegularFile(path)) return null
    return runCatching { BatchAlignmentCodec.decode(Files.readString(path)) }
        .getOrNull()
        ?.takeIf { it.isValidFor(chunk.text, sha, durationSeconds) }
}
