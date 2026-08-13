package com.qwen.tts.studio.batch

/**
 * Builds the final chunk plan for batch generation.
 *
 * Every returned chunk is a contiguous substring of [source]. Joining the
 * returned chunks with an empty separator therefore reproduces [source]
 * exactly. Paragraph separators are kept with the paragraph before them so a
 * chunk boundary never requires inventing whitespace later.
 */
object TextBatching {
    const val DEFAULT_MAX_CHARACTERS = 5_000

    /** Lossless-to-source synthesis cleanup; manifests retain the original text. */
    fun cleanForSynthesis(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n+ *"), "\n")
        .trim()

    fun packParagraphs(source: String, maxCharacters: Int = DEFAULT_MAX_CHARACTERS): List<String> {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        val separatorPattern = Regex("(?:\\r?\\n)[\\t ]*(?:\\r?\\n)+")
        val segments = mutableListOf<String>()
        var cursor = 0
        separatorPattern.findAll(source).forEach { match ->
            segments += source.substring(cursor, match.range.last + 1)
            cursor = match.range.last + 1
        }
        if (cursor < source.length) segments += source.substring(cursor)

        val batches = mutableListOf<String>()
        var current = StringBuilder()
        segments.forEach { segment ->
            if (segment.isEmpty()) return@forEach
            if (segment.length > maxCharacters) {
                if (current.isNotEmpty()) {
                    batches += current.toString()
                    current = StringBuilder()
                }
                batches += splitLosslessly(segment, maxCharacters)
                return@forEach
            }
            if (current.length + segment.length > maxCharacters && current.isNotEmpty()) {
                batches += current.toString()
                current = StringBuilder()
            }
            current.append(segment)
        }
        if (current.isNotEmpty()) batches += current.toString()
        return batches
    }

    fun packParagraphsMeasured(
        source: String,
        maxCharacters: Int,
        maxTextTokens: Int,
        tokenCount: ((String) -> Int)?
    ): List<String> {
        val characterBatches = packParagraphs(source, maxCharacters)
        if (tokenCount == null) return characterBatches
        return characterBatches.flatMap { splitMeasured(it, maxTextTokens, tokenCount) }
    }

    private fun splitMeasured(text: String, maxTokens: Int, tokenCount: (String) -> Int): List<String> {
        if (tokenCount(text) <= maxTokens) return listOf(text)
        val words = text.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        var current = StringBuilder()
        for (part in words) {
            val candidate = current.toString() + part
            if (current.isNotEmpty() && tokenCount(candidate) > maxTokens) {
                result += current.toString()
                current = StringBuilder(part.trimStart())
            } else {
                current.append(part)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result.flatMap { if (tokenCount(it) > maxTokens && it.length > 1) splitMeasured(it, maxTokens, tokenCount) else listOf(it) }
    }

    private fun splitLosslessly(text: String, maxCharacters: Int): List<String> {
        val batches = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            val remaining = text.length - offset
            if (remaining <= maxCharacters) {
                batches += text.substring(offset)
                break
            }
            var cut = maxCharacters
            for (index in maxCharacters downTo 1) {
                if (text[offset + index - 1].isWhitespace()) {
                    cut = index
                    break
                }
            }
            batches += text.substring(offset, offset + cut)
            offset += cut
        }
        return batches
    }
}
