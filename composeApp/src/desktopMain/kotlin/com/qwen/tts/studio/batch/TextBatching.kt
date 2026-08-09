package com.qwen.tts.studio.batch

/** Packs complete paragraphs up to the Studio input limit without dropping source text. */
object TextBatching {
    const val DEFAULT_MAX_CHARACTERS = 5_000

    fun packParagraphs(source: String, maxCharacters: Int = DEFAULT_MAX_CHARACTERS): List<String> {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val separatorPattern = Regex("\\n[\\t ]*\\n+")
        val segments = mutableListOf<String>()
        var cursor = 0
        var pendingSeparator = ""
        separatorPattern.findAll(normalized).forEach { match ->
            segments += pendingSeparator + normalized.substring(cursor, match.range.first)
            pendingSeparator = match.value
            cursor = match.range.last + 1
        }
        segments += pendingSeparator + normalized.substring(cursor)

        val batches = mutableListOf<String>()
        var current = StringBuilder()
        segments.filter(String::isNotEmpty).forEach { segment ->
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
