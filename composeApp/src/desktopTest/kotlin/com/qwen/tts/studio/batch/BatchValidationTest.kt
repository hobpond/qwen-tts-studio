package com.qwen.tts.studio.batch

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchValidationTest {
    @Test
    fun asrValidationChecksBothPrefixAndSuffixAndReportsLowSimilarity() {
        val root = Files.createTempDirectory("batch-asr-validation")
        val store = BatchAudioStore(root)
        val text = "The beginning of the sentence. The ending of the sentence."
        val manifest = store.writeChunk(store.createManifest("asr", 1), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), text)
        val findings = BatchAsrValidator.validate(store, manifest, transcriber = BatchAsrTranscriber { _, window ->
            if (window == AsrWindow.PREFIX) text else "wrong ending"
        })

        assertEquals(2, findings.size)
        val prefix = findings.first { it.window == AsrWindow.PREFIX }
        val suffix = findings.first { it.window == AsrWindow.SUFFIX }
        assertEquals(0, prefix.chunkIndex)
        assertEquals(BatchAsrValidator.expectedSegment(text, AsrWindow.PREFIX), prefix.expectedText)
        assertEquals(text, prefix.transcript)
        assertEquals(1.0, prefix.score)
        assertNull(prefix.error)
        assertTrue(suffix.expectedText.isNotBlank())
        assertTrue(suffix.score!! < 0.75)
        assertTrue(suffix.error!!.contains("0.75"))
    }

    @Test
    fun localScoringAcceptsExpectedEdgeTextWithAdjacentSpeech() {
        val expected = "The first sentence is spoken clearly. The second sentence ends the chunk."
        val prefix = BatchAsrValidator.compare(
            expected,
            "A valid neighboring phrase. The first sentence is spoken clearly. Another valid phrase.",
            AsrWindow.PREFIX
        )
        val suffix = BatchAsrValidator.compare(
            expected,
            "A valid neighboring phrase. The second sentence ends the chunk. Another valid phrase.",
            AsrWindow.SUFFIX
        )
        val unrelated = BatchAsrValidator.compare(
            expected,
            "This transcript describes a completely unrelated topic with different wording.",
            AsrWindow.PREFIX
        )

        assertTrue(prefix.similarity >= 0.99, "prefix score=${prefix.similarity}")
        assertTrue(suffix.similarity >= 0.99, "suffix score=${suffix.similarity}")
        assertTrue(unrelated.similarity < 0.75, "unrelated score=${unrelated.similarity}")
    }

    @Test
    fun failedTranscriptionStillReturnsReviewableExpectedSegment() {
        val root = Files.createTempDirectory("batch-asr-error")
        val store = BatchAudioStore(root)
        val text = "A chunk whose expected speech should remain visible when ASR fails."
        val manifest = store.writeChunk(
            store.createManifest("asr-error", 1),
            0,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            text
        )
        val finding = BatchAsrValidator.validate(store, manifest, transcriber = BatchAsrTranscriber { _, _ ->
            error("native decoder unavailable")
        }).single { it.window == AsrWindow.PREFIX }

        assertEquals(0, finding.chunkIndex)
        assertTrue(finding.expectedText.isNotBlank())
        assertNull(finding.transcript)
        assertNull(finding.score)
        assertEquals("native decoder unavailable", finding.error)
    }

    @Test
    fun deterministicValidationTreatsCrLfSourceAndLfSidecarAsEquivalent() {
        val root = Files.createTempDirectory("batch-crlf-source")
        val store = BatchAudioStore(root)
        val source = "First line.\r\nSecond line.\r\n"
        val manifest = store.generateManifestFromTexts("crlf-source", listOf(source))
        Files.writeString(root.resolve("chunk-000000.txt"), source.replace("\r\n", "\n"))

        val report = BatchValidator.validate(store, manifest, source)

        assertTrue(report.passed)
        assertTrue(report.findings.none { it.code == "TEXT_MANIFEST_MISMATCH" })
        assertTrue(report.findings.none { it.code == "SOURCE_ROUND_TRIP_FAILED" })
    }

    @Test
    fun deterministicValidationTreatsLfSourceAndCrLfSidecarAsEquivalent() {
        val root = Files.createTempDirectory("batch-lf-source")
        val store = BatchAudioStore(root)
        val source = "First line.\nSecond line.\n"
        val manifest = store.generateManifestFromTexts("lf-source", listOf(source))
        Files.writeString(root.resolve("chunk-000000.txt"), source.replace("\n", "\r\n"))

        val report = BatchValidator.validate(store, manifest, source)

        assertTrue(report.passed)
        assertTrue(report.findings.none { it.code == "TEXT_MANIFEST_MISMATCH" })
        assertTrue(report.findings.none { it.code == "SOURCE_ROUND_TRIP_FAILED" })
    }
}
