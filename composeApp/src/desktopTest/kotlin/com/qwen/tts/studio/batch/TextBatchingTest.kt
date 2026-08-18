package com.qwen.tts.studio.batch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextBatchingTest {
    @Test
    fun synthesisCleanupRemovesWhitespaceNoiseWithoutChangingWords() {
        assertEquals("First line\nSecond line", TextBatching.cleanForSynthesis("  First   line\r\n\n\t Second line  "))
    }

    @Test
    fun measuredPackingUsesTokenCounterWithoutChangingText() {
        val source = "one two three four five six seven eight nine ten"
        val chunks = TextBatching.packParagraphsMeasured(source, 5_000, 4) { value ->
            value.trim().split(Regex("\\s+")).count()
        }

        assertTrue(chunks.all { it.trim().split(Regex("\\s+")).size <= 4 })
        assertEquals(source, chunks.joinToString(""))
    }

    @Test
    fun combinesCompleteParagraphsWithoutExceedingLimit() {
        assertEquals(
            listOf("one two\n\n", "alpha beta\n\ngamma"),
            TextBatching.packParagraphs("one two\n\nalpha beta\n\ngamma", 20)
        )
    }

    @Test
    fun splitsAnOverlongWordWithoutExceedingLimit() {
        val result = TextBatching.packParagraphs("abcdefghij tail", 4)
        assertEquals(listOf("abcd", "efgh", "ij ", "tail"), result)
        assertTrue(result.all { it.length <= 4 })
    }

    @Test
    fun smallCharacterPlansKeepSeparatorsOutOfStandaloneChunks() {
        val source = "A\n\nB"
        val chunks = TextBatching.packParagraphsForGeneration(source, 1)

        assertEquals(source, chunks.joinToString(separator = ""))
        assertTrue(chunks.all(String::isNotBlank))
    }

    @Test
    fun generationPlansKeepClosingPunctuationWithSpeech() {
        val source = "第一句内容很长很长很长很长。\"\r\n第二句内容也很长很长很长。"
        val chunks = TextBatching.packParagraphsForGeneration(source, 16)

        assertEquals(source, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { piece -> piece.any(Char::isLetterOrDigit) })
    }

    @Test
    fun losslessSplittingPrefersSentenceBoundariesForCjkText() {
        val source = "第一句内容很长很长。第二句内容也很长很长。第三句继续测试。"
        val chunks = TextBatching.packParagraphs(source, 16)

        assertEquals(source, chunks.joinToString(separator = ""))
        assertTrue(chunks.dropLast(1).all { it.endsWith('。') })
    }

    @Test
    fun rebalancesNearLimitTailInsteadOfCreatingTinyAudioChunk() {
        val source = "word ".repeat(68)
        val chunks = TextBatching.packParagraphs(source, 336)

        assertEquals(source, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { it.length <= 336 })
        assertTrue(chunks.all { it.length >= 64 })
    }

    @Test
    fun generationPackingRebalancesSmallRecoveryTails() {
        val source = "中".repeat(44)
        val chunks = TextBatching.packParagraphsForGeneration(source, 20)

        assertEquals(source, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { it.length <= 20 })
        assertTrue(chunks.all { it.length >= 10 })
    }

    @Test
    fun treatsWhitespaceOnlyLinesAsParagraphSeparators() {
        assertEquals(
            listOf("first paragraph\n \t\nsecond paragraph"),
            TextBatching.packParagraphs("first paragraph\n \t\nsecond paragraph", 100)
        )
    }

    @Test
    fun preservesEveryCharacterIncludingLineEndingsAndSeparators() {
        val source = "\r\nfirst\r\n\r\nsecond\n \t\nthird\r"
        val chunks = TextBatching.packParagraphs(source, 7)

        assertEquals(source, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { it.length <= 7 })
    }

    @Test
    fun t1RoundTripsWithoutMissingCharacters() {
        val path = java.nio.file.Path.of("D:\\t1.txt")
        if (!java.nio.file.Files.isRegularFile(path)) return
        val source = java.nio.file.Files.readString(path)
        val chunks = TextBatching.packParagraphs(source, 5_000)

        assertTrue(chunks.isNotEmpty())
        assertEquals(source, chunks.joinToString(separator = ""))
        assertEquals(source.length, chunks.sumOf(String::length))
        assertTrue(chunks.all { it.length <= 5_000 })
    }

    @Test
    fun adaptiveAudioReplanIsLosslessAndMakesContextLimitedPiecesSafe() {
        val source = "word ".repeat(240)
        val tokenCount = { text: String -> text.length * 5 }

        val pieces = TextBatching.replanForAudioBudget(
            source = source,
            customVoice = true,
            textTokenCount = tokenCount,
            instruction = "Calm narrator"
        )

        assertTrue(pieces.size > 1)
        assertEquals(source, pieces.joinToString(separator = ""))
        assertTrue(
            pieces.all {
                !BatchMemoryPolicy.audioBudget(
                    TextBatching.cleanForSynthesis(it),
                    customVoice = true,
                    textTokenCount = tokenCount(TextBatching.cleanForSynthesis(it)),
                    instruction = "Calm narrator"
                ).requiresRechunk
            }
        )
    }

    @Test
    fun adaptiveAudioReplanMergesShortHeadingIntoSafeNeighbor() {
        val source = "A Gardener's Touch\n\nPart I\n\n" + "word ".repeat(240)
        val tokenCount = { text: String -> text.length * 5 }

        val pieces = TextBatching.replanForAudioBudget(
            source = source,
            customVoice = true,
            textTokenCount = tokenCount,
            instruction = "Calm narrator"
        )

        assertEquals(source, pieces.joinToString(separator = ""))
        assertTrue(pieces.first().length >= 64)
        assertTrue(
            pieces.all {
                BatchMemoryPolicy.audioBudget(
                    TextBatching.cleanForSynthesis(it),
                    customVoice = true,
                    textTokenCount = tokenCount(TextBatching.cleanForSynthesis(it)),
                    instruction = "Calm narrator"
                ).hasNativeHeadroom
            }
        )
    }

    @Test
    fun adaptiveAudioReplanDoesNotReturnWhitespaceOnlyPieces() {
        val source = "A\n\nB"
        val tokenCount = { text: String -> text.length * 3_500 }

        val pieces = TextBatching.replanForAudioBudget(
            source = source,
            customVoice = true,
            textTokenCount = tokenCount,
            instruction = null
        )

        assertEquals(source, pieces.joinToString(separator = ""))
        assertTrue(pieces.all(String::isNotBlank))
    }
}
