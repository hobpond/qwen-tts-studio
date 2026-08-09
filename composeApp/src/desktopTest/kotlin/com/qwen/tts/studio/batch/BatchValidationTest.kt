package com.qwen.tts.studio.batch

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(findings.first { it.window == AsrWindow.PREFIX }.error == null)
        assertTrue(findings.first { it.window == AsrWindow.SUFFIX }.error != null)
    }
}
