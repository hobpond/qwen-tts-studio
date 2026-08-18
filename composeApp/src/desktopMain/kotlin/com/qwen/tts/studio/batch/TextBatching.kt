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
    const val DEFAULT_CHINESE_VIVIAN_CHARACTERS = 80
    const val DEFAULT_RECHUNK_CHARACTERS = 40
    private const val MIN_AUDIO_CHUNK_CHARACTERS = 64
    private const val MIN_SPLIT_REMAINDER_CHARACTERS = 64

    /**
     * Returns the source-plan target for the named Chinese/Vivian reliability
     * profile. The smaller recovery target is intentionally separate so a
     * normal batch does not inherit the cost of failed-chunk repair.
     */
    fun defaultProfileCharacters(language: String?, speaker: String?): Int =
        if (language.orEmpty().matches(Regex("(?i)Chinese|Mandarin")) &&
            speaker.orEmpty().equals("Vivian", ignoreCase = true)
        ) {
            DEFAULT_CHINESE_VIVIAN_CHARACTERS
        } else {
            DEFAULT_MAX_CHARACTERS
        }

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

    /**
     * Builds a lossless plan that is safe to submit as generation requests.
     * Character-only packing may produce a whitespace- or punctuation-only
     * fragment when a very small limit cuts through a paragraph separator or
     * quote; generation plans attach that fragment to an adjacent speech
     * chunk instead.
     */
    fun packParagraphsForGeneration(
        source: String,
        maxCharacters: Int = DEFAULT_MAX_CHARACTERS
    ): List<String> = rebalanceShortTails(
        mergeNonSpeechOnlyPieces(packParagraphs(source, maxCharacters)),
        maxCharacters
    )

    fun packParagraphsMeasured(
        source: String,
        maxCharacters: Int,
        maxTextTokens: Int,
        tokenCount: ((String) -> Int)?
    ): List<String> {
        val characterBatches = packParagraphsForGeneration(source, maxCharacters)
        if (tokenCount == null) return characterBatches
        return characterBatches.flatMap { splitMeasured(it, maxTextTokens, tokenCount) }
    }

    /**
     * Replans only chunks whose duration budget is limited by the native text/context
     * budget. The returned pieces are lossless substrings of [source], so joining them
     * with an empty separator reconstructs the original manifest text.
     */
    fun replanForAudioBudget(
        source: String,
        customVoice: Boolean,
        textTokenCount: ((String) -> Int)?,
        instruction: String?
    ): List<String> {
        require(source.isNotBlank()) { "source must not be blank" }

        fun budgetFor(text: String): BatchAudioBudget = BatchMemoryPolicy.audioBudget(
            text = cleanForSynthesis(text),
            customVoice = customVoice,
            textTokenCount = textTokenCount?.invoke(cleanForSynthesis(text)),
            instruction = instruction
        )

        fun maxSafeCharacters(text: String): Int {
            var low = 1
            var high = text.length
            while (low < high) {
                val candidateLength = (low + high + 1) / 2
                val candidate = text.substring(0, candidateLength)
                if (!budgetFor(candidate).requiresRechunk) low = candidateLength else high = candidateLength - 1
            }
            return low.coerceAtLeast(1)
        }

        fun split(text: String): List<String> {
            val budget = budgetFor(text)
            if (!budget.requiresRechunk) return listOf(text)
            if (text.length <= 1) {
                error(
                    "Audio context cannot safely fit even one synthesis character " +
                        "(${budget.contextTokens} context tokens available; " +
                        "${BatchMemoryPolicy.MIN_AUDIO_CONTEXT_TOKENS} required)."
                )
            }
            val safeCharacters = maxSafeCharacters(text)
            if (safeCharacters <= 1 && budgetFor(text.substring(0, 1)).requiresRechunk) {
                error(
                    "Audio context cannot safely fit a re-planned chunk; " +
                        "reduce the instruction or choose a model with more context."
                )
            }
            // A very small safe character budget can split a paragraph
            // separator or closing quote into a non-speech-only piece. Those
            // pieces preserve the source bytes but are poor native generation
            // requests. Keep each separator losslessly, but attach it to a
            // neighboring synthesis piece before recursing into the split.
            val pieces = mergeNonSpeechOnlyPieces(packParagraphs(text, safeCharacters))
            val progressed = pieces.size > 1 || pieces.singleOrNull() != text
            return if (progressed) {
                pieces.flatMap(::split)
            } else {
                text.chunked(safeCharacters).flatMap(::split)
            }
        }

        return mergeShortAudioPieces(split(source), ::budgetFor)
    }

    /**
     * Paragraph packing can leave a short heading or trailing fragment beside
     * a full paragraph. Those pieces are valid from a text-budget perspective
     * but are poor standalone ASR units. Merge them with an adjacent piece
     * whenever the combined native budget remains safe; never invent or drop
     * a separator while doing so.
     */
    private fun mergeShortAudioPieces(
        pieces: List<String>,
        budgetFor: (String) -> BatchAudioBudget
    ): List<String> {
        val merged = pieces.toMutableList()
        var index = 0
        while (index < merged.size) {
            if (merged.size == 1 || merged[index].length >= MIN_AUDIO_CHUNK_CHARACTERS) {
                index++
                continue
            }

            val next = merged.getOrNull(index + 1)
            if (next != null && budgetFor(merged[index] + next).hasNativeHeadroom) {
                merged[index] += next
                merged.removeAt(index + 1)
                continue
            }

            val previous = merged.getOrNull(index - 1)
            if (previous != null && budgetFor(previous + merged[index]).hasNativeHeadroom) {
                merged[index - 1] += merged[index]
                merged.removeAt(index)
                index--
                continue
            }

            // No safe adjacent merge exists. Keep the lossless piece and let
            // ASR validation report the limitation instead of exceeding the
            // native aggregate cap.
            index++
        }
        return merged
    }

    /**
     * Keeps non-speech-only source fragments lossless without returning them
     * as standalone generation chunks. A leading fragment is attached to the
     * next speech piece; an internal or trailing fragment stays with the
     * preceding piece, matching separator ownership in [packParagraphs].
     */
    private fun mergeNonSpeechOnlyPieces(pieces: List<String>): List<String> {
        if (pieces.size <= 1) return pieces

        val merged = mutableListOf<String>()
        var leadingNonSpeech = StringBuilder()
        pieces.forEach { piece ->
            if (piece.isNonSpeechOnly()) {
                if (merged.isEmpty()) {
                    leadingNonSpeech.append(piece)
                } else {
                    merged[merged.lastIndex] += piece
                }
            } else if (leadingNonSpeech.isNotEmpty()) {
                merged += leadingNonSpeech.toString() + piece
                leadingNonSpeech = StringBuilder()
            } else {
                merged += piece
            }
        }
        if (leadingNonSpeech.isNotEmpty()) {
            if (merged.isEmpty()) {
                merged += leadingNonSpeech.toString()
            } else {
                merged[merged.lastIndex] += leadingNonSpeech
            }
        }
        return merged
    }

    /**
     * Rebalances the final pieces when a character limit would otherwise leave
     * a tiny tail. The pieces remain contiguous and lossless, and no piece is
     * allowed to exceed the requested limit. This matters especially during
     * failed-chunk recovery, where a 20-character split can otherwise produce
     * one- to six-character ASR units that are poor validation targets.
     */
    private fun rebalanceShortTails(pieces: List<String>, maxCharacters: Int): List<String> {
        if (pieces.size <= 1) return pieces
        val minimum = minOf(MIN_AUDIO_CHUNK_CHARACTERS, maxCharacters / 2).coerceAtLeast(1)
        val balanced = pieces.toMutableList()
        while (balanced.size > 1 && balanced.last().length < minimum) {
            val splitAt = balanced.lastIndex - 1
            val combined = balanced[splitAt] + balanced[splitAt + 1]
            balanced.subList(splitAt, splitAt + 2).clear()

            val pieceCount = ((combined.length + maxCharacters - 1) / maxCharacters).coerceAtLeast(1)
            val baseLength = combined.length / pieceCount
            val remainder = combined.length % pieceCount
            var cursor = 0
            repeat(pieceCount) { part ->
                val length = baseLength + if (part < remainder) 1 else 0
                balanced.add(splitAt + part, combined.substring(cursor, cursor + length))
                cursor += length
            }
        }
        return balanced
    }

    private fun String.isNonSpeechOnly(): Boolean =
        isBlank() || none(Char::isLetterOrDigit)

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
            // Avoid producing a tiny tail (for example, "was his ") that is
            // too short for reliable ASR. Rebalance near-limit splits before
            // searching backward for a lossless whitespace boundary.
            val sentenceBoundaryAvailable = (maxCharacters downTo 1).any { index ->
                isSentenceBoundary(text[offset + index - 1])
            }
            val target = if (!sentenceBoundaryAvailable && maxCharacters >= MIN_SPLIT_REMAINDER_CHARACTERS &&
                remaining - maxCharacters in 1 until MIN_SPLIT_REMAINDER_CHARACTERS
            ) {
                remaining / 2
            } else {
                maxCharacters
            }
            val sentenceCut = (target downTo 1)
                .firstOrNull { index -> isSentenceBoundary(text[offset + index - 1]) }
            val whitespaceCut = (target downTo 1)
                .firstOrNull { index -> text[offset + index - 1].isWhitespace() }
            val cut = sentenceCut ?: whitespaceCut ?: target
            batches += text.substring(offset, offset + cut)
            offset += cut
        }
        return batches
    }

    private fun isSentenceBoundary(character: Char): Boolean = character in setOf(
        '.', '!', '?', ';',
        '。', '！', '？', '；'
    )
}
