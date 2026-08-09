package com.qwen.tts.studio.batch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextBatchingTest {
    @Test
    fun combinesCompleteParagraphsWithoutExceedingLimit() {
        assertEquals(
            listOf("one two\n\nalpha beta", "\n\ngamma"),
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
    fun treatsWhitespaceOnlyLinesAsParagraphSeparators() {
        assertEquals(
            listOf("first paragraph\n \t\nsecond paragraph"),
            TextBatching.packParagraphs("first paragraph\n \t\nsecond paragraph", 100)
        )
    }

    @Test
    fun t1RoundTripsWithoutMissingCharacters() {
        val path = java.nio.file.Path.of("D:\\t1.txt")
        if (!java.nio.file.Files.isRegularFile(path)) return
        val source = java.nio.file.Files.readString(path).replace("\r\n", "\n").replace('\r', '\n')
        val chunks = TextBatching.packParagraphs(source, 5_000)

        assertTrue(chunks.isNotEmpty())
        assertEquals(6, chunks.size)
        assertEquals(source, chunks.joinToString(separator = ""))
        assertEquals(source.length, chunks.sumOf(String::length))
        assertTrue(chunks.all { it.length <= 5_000 })
    }
}
