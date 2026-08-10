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
