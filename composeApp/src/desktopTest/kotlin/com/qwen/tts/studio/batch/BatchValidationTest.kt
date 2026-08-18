package com.qwen.tts.studio.batch

import androidx.lifecycle.ViewModelStore
import com.qwen.tts.studio.viewmodel.StudioViewModel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun localScoringAcceptsFiveSecondPrefixWithMinorAsrSpellingDifferences() {
        val expected = "A gardener's touch, Part I. Grenville McKree was born too big; the seventh son was born to Margaret McKree."
        val transcript = "The gardener's touch. Part I. Granville McCree was born."

        val result = BatchAsrValidator.compare(expected, transcript, AsrWindow.PREFIX)

        assertTrue(result.similarity >= 0.75, "prefix score=${result.similarity}")
    }

    @Test
    fun localScoringAcceptsCjkFiveSecondPrefixThatEndsMidClause() {
        val expected = "好久好久之后，只见美少女浑身一顿，呻吟一声，立即静静地伏在心上人的身上。"
        val transcript = "好久好久之后，只见美。"

        val result = BatchAsrValidator.compare(expected, transcript, AsrWindow.PREFIX)

        assertTrue(result.similarity >= 0.75, "prefix score=${result.similarity}")
    }

    @Test
    fun localScoringAcceptsCjkSuffixAndChineseNumeralVariants() {
        val expected = "空有旷世奇宝，却参悟不出，岂不。"
        val transcript = "旷世奇宝，却参悟不出，岂不。"

        val result = BatchAsrValidator.compare(expected, transcript, AsrWindow.SUFFIX)
        val numeralResult = BatchAsrValidator.compare(
            "一版一印：1993年3月15日",
            "一版一印，一九九三年三月。",
            AsrWindow.SUFFIX
        )
        val asrSpellingResult = BatchAsrValidator.compare(
            "印张：16 印数：20000册",
            "印章十六印数，两万册。",
            AsrWindow.PREFIX
        )

        assertTrue(result.similarity >= 0.75, "suffix score=${result.similarity}")
        assertTrue(numeralResult.similarity >= 0.75, "numeral suffix score=${numeralResult.similarity}")
        assertTrue(asrSpellingResult.similarity >= 0.75, "ASR spelling score=${asrSpellingResult.similarity}")
    }

    @Test
    fun localScoringAllowsSmallCjkEdgeDriftAndPhoneticSubstitutions() {
        val result = BatchAsrValidator.compare(
            "篇时而刀光剑影，杀气震天，惊心动魄。",
            "夜，刀光剑影。",
            AsrWindow.PREFIX
        )

        assertTrue(result.similarity >= 0.75, "drift score=${result.similarity}")
    }

    @Test
    fun localScoringRejectsUnrelatedCjkTranscript() {
        val expected = "好久好久之后，只见美少女浑身一顿，呻吟一声。"
        val transcript = "山间的风吹过树林，远处传来流水的声音。"

        val result = BatchAsrValidator.compare(expected, transcript, AsrWindow.PREFIX)

        assertTrue(result.similarity < 0.75, "unrelated score=${result.similarity}")
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
    fun asrValidationCapturesPerWindowTimingAndCpuBackendByDefault() {
        val root = Files.createTempDirectory("batch-asr-metrics")
        val store = BatchAudioStore(root)
        val text = "The beginning of the sentence. The ending of the sentence."
        val manifest = store.writeChunk(
            store.createManifest("asr-metrics", 1),
            0,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            text
        )

        val result = BatchAsrValidator.validateWithMetrics(
            store,
            manifest,
            BatchAsrTranscriber { _, _ -> text }
        )

        assertTrue(result.passed)
        assertEquals(2, result.metrics.windowCount)
        assertEquals(1, result.metrics.chunkCount)
        assertEquals(AsrBackend.CPU, result.metrics.backend)
        assertEquals(setOf(AsrBackend.CPU), result.metrics.backendEvidence)
        assertTrue(result.metrics.totalElapsedMs >= 0)
        assertEquals(result.metrics.totalElapsedMs, result.metrics.chunkElapsedMs[0])
        assertEquals(
            result.metrics.totalElapsedMs,
            result.findings.sumOf { it.elapsedMs ?: error("ASR finding is missing elapsed time") }
        )
        result.findings.forEach { finding ->
            assertEquals(AsrBackend.CPU, finding.backend)
            assertTrue((finding.elapsedMs ?: -1) >= 0)
        }
    }

    @Test
    fun asrValidationCanInspectOnlySelectedCompleteChunks() {
        val root = Files.createTempDirectory("batch-asr-selected")
        val store = BatchAudioStore(root)
        var manifest = store.createManifest("asr-selected", 2)
        manifest = store.writeChunk(
            manifest,
            0,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            "First selected chunk."
        )
        manifest = store.writeChunk(
            manifest,
            1,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            "Second selected chunk."
        )

        val result = BatchAsrValidator.validateWithMetrics(
            store,
            manifest,
            BatchAsrTranscriber { wav, _ ->
                if (wav.fileName.toString().contains("000001")) "Second selected chunk." else "wrong"
            },
            chunkIndices = setOf(1)
        )

        assertTrue(result.passed)
        assertEquals(setOf(1), result.findings.map { it.chunkIndex }.toSet())
        assertEquals(2, result.metrics.windowCount)
        assertEquals(1, result.metrics.chunkCount)
    }

    @Test
    fun asrValidationCarriesAuxiliaryModelFingerprintMetadata() {
        val root = Files.createTempDirectory("batch-asr-model-provenance")
        val store = BatchAudioStore(root)
        val manifest = store.writeChunk(
            store.createManifest("asr-model-provenance", 1),
            0,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            "A chunk with model provenance."
        )
        val transcriber = object : BatchAsrTranscriber {
            override val modelMetadata = mapOf(
                "asrModelFileSha256" to "asr-fingerprint",
                "asrModelFileSizeBytes" to "123"
            )

            override fun transcribe(wav: java.nio.file.Path, window: AsrWindow): String =
                manifest.chunks.single().text
        }

        val result = BatchAsrValidator.validateWithMetrics(store, manifest, transcriber)

        assertEquals("asr-fingerprint", result.modelMetadata["asrModelFileSha256"])
        assertEquals("123", result.modelMetadata["asrModelFileSizeBytes"])
    }

    @Test
    fun asrMetricsCaptureFailedTranscriptionAndPreserveFailureSemantics() {
        val root = Files.createTempDirectory("batch-asr-metrics-error")
        val store = BatchAudioStore(root)
        val manifest = store.writeChunk(
            store.createManifest("asr-metrics-error", 1),
            0,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            "A chunk that will fail ASR."
        )
        val transcriber = object : BatchAsrTranscriber {
            override val backend = AsrBackend.UNKNOWN

            override fun transcribe(wav: java.nio.file.Path, window: AsrWindow): String =
                error("decoder unavailable")
        }

        val result = BatchAsrValidator.validateWithMetrics(store, manifest, transcriber)

        assertFalse(result.passed)
        assertEquals(AsrBackend.UNKNOWN, result.metrics.backend)
        assertTrue(result.findings.all { !it.passed })
        assertTrue(result.findings.all { it.backend == AsrBackend.UNKNOWN })
        assertTrue(result.findings.all { (it.elapsedMs ?: -1) >= 0 })
        assertTrue(result.findings.all { it.error == "decoder unavailable" })
    }

    @Test
    fun asrMetricsAggregateWindowEvidenceByChunkAndMarkMixedBackendsUnknown() {
        val metrics = AsrValidationMetrics(
            listOf(
                AsrValidationWindowMetrics(0, AsrWindow.PREFIX, 12, AsrBackend.CPU),
                AsrValidationWindowMetrics(0, AsrWindow.SUFFIX, 8, AsrBackend.CPU),
                AsrValidationWindowMetrics(1, AsrWindow.PREFIX, 20, AsrBackend.CPU)
            )
        )

        assertEquals(40, metrics.totalElapsedMs)
        assertEquals(mapOf(0 to 20L, 1 to 20L), metrics.chunkElapsedMs)
        assertEquals(20, metrics.perChunk.getValue(0).elapsedMs)
        assertEquals(2, metrics.perChunk.getValue(0).windows.size)
        assertEquals(AsrBackend.CPU, metrics.backend)

        val mixed = AsrValidationMetrics(
            listOf(
                AsrValidationWindowMetrics(0, AsrWindow.PREFIX, 1, AsrBackend.CPU),
                AsrValidationWindowMetrics(0, AsrWindow.SUFFIX, 1, AsrBackend.GPU)
            )
        )
        assertEquals(setOf(AsrBackend.CPU, AsrBackend.GPU), mixed.backendEvidence)
        assertEquals(AsrBackend.UNKNOWN, mixed.backend)
    }

    @Test
    fun deterministicValidationRejectsPendingChunkWithActionableError() {
        val root = Files.createTempDirectory("batch-pending-validation")
        val store = BatchAudioStore(root)
        val manifest = store.generateManifestFromTexts("pending", listOf("Pending chunk."))

        val report = BatchValidator.validate(store, manifest)

        val finding = report.findings.single { it.code == "CHUNK_NOT_COMPLETE" }
        assertEquals(ValidationSeverity.ERROR, finding.severity)
        assertEquals(0, finding.chunkIndex)
        assertTrue(finding.message.contains("generate or resume"))
        assertFalse(report.passed)
    }

    @Test
    fun deterministicValidationRejectsFailedChunkWithActionableError() {
        val root = Files.createTempDirectory("batch-failed-validation")
        val store = BatchAudioStore(root)
        val pending = store.generateManifestFromTexts("failed", listOf("Failed chunk."))
        val manifest = store.markFailed(pending, 0, "generation failed")

        val report = BatchValidator.validate(store, manifest)

        val finding = report.findings.single { it.code == "CHUNK_NOT_COMPLETE" }
        assertEquals(ValidationSeverity.ERROR, finding.severity)
        assertTrue(finding.message.contains("FAILED"))
        assertTrue(finding.message.contains("generate or resume"))
        assertFalse(report.passed)
    }

    @Test
    fun deterministicValidationPassesStructurallyValidCompleteChunk() {
        val root = Files.createTempDirectory("batch-complete-validation")
        val store = BatchAudioStore(root)
        val manifest = completeManifest(store, "complete", "A complete chunk.")

        val report = BatchValidator.validate(store, manifest)

        assertTrue(report.passed)
        assertTrue(report.findings.any { it.code == "VALID" })
    }

    @Test
    fun viewModelValidationNeverPersistsPassForIncompleteChunks() = runBlocking {
        val pendingRoot = Files.createTempDirectory("batch-pending-persist")
        val pendingStore = BatchAudioStore(pendingRoot)
        pendingStore.generateManifestFromTexts("pending-persist", listOf("Pending chunk."))
        val pendingViewModel = StudioViewModel()
        try {
            pendingViewModel.validateAllBatchChunks(pendingRoot.resolve("manifest.json").toFile(), null).join()

            val persisted = pendingStore.loadManifest()
            val chunk = persisted.chunks.single()
            assertEquals(false, chunk.validationPassed)
            assertTrue(chunk.validationMessage.orEmpty().contains("generate or resume"))
            assertEquals(false, pendingViewModel.batchState.value.chunkValidation[0]?.passed)
        } finally {
            closeForTest(pendingViewModel)
        }

        val failedRoot = Files.createTempDirectory("batch-failed-persist")
        val failedStore = BatchAudioStore(failedRoot)
        val failed = failedStore.generateManifestFromTexts("failed-persist", listOf("Failed chunk."))
        failedStore.markFailed(failed, 0, "generation failed")
        val failedViewModel = StudioViewModel()
        try {
            failedViewModel.validateBatchChunk(failedRoot.resolve("manifest.json").toFile(), 0, null).join()

            val persisted = failedStore.loadManifest()
            val chunk = persisted.chunks.single()
            assertEquals(false, chunk.validationPassed)
            assertTrue(chunk.validationMessage.orEmpty().contains("FAILED"))
            assertEquals(false, failedViewModel.batchState.value.chunkValidation[0]?.passed)
        } finally {
            closeForTest(failedViewModel)
        }
    }

    @Test
    fun deterministicValidationTreatsCrLfSourceAndLfSidecarAsEquivalent() {
        val root = Files.createTempDirectory("batch-crlf-source")
        val store = BatchAudioStore(root)
        val source = "First line.\r\nSecond line.\r\n"
        val manifest = completeManifest(store, "crlf-source", source)
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
        val manifest = completeManifest(store, "lf-source", source)
        Files.writeString(root.resolve("chunk-000000.txt"), source.replace("\n", "\r\n"))

        val report = BatchValidator.validate(store, manifest, source)

        assertTrue(report.passed)
        assertTrue(report.findings.none { it.code == "TEXT_MANIFEST_MISMATCH" })
        assertTrue(report.findings.none { it.code == "SOURCE_ROUND_TRIP_FAILED" })
    }

    @Test
    fun deterministicValidationIgnoresSynthesisWhitespaceCleanup() {
        val root = Files.createTempDirectory("batch-cleaned-whitespace")
        val store = BatchAudioStore(root)
        val source = "  First   line.\r\n\r\n\tSecond\tline.  "
        val manifest = completeManifest(store, "cleaned-whitespace", source)
        Files.writeString(root.resolve("chunk-000000.txt"), "First line.\nSecond line.")

        val report = BatchValidator.validate(store, manifest, source)

        assertTrue(report.passed)
        assertTrue(report.findings.none { it.code == "TEXT_MANIFEST_MISMATCH" })
        assertTrue(report.findings.none { it.code == "SOURCE_ROUND_TRIP_FAILED" })
    }

    @Test
    fun durationFloorUsesCleanedSynthesisTextRatherThanRawManifestText() {
        val root = Files.createTempDirectory("batch-cleaned-duration")
        val store = BatchAudioStore(root)
        val rawText = "word   ".repeat(40)
        val cleanedText = TextBatching.cleanForSynthesis(rawText)
        assertTrue(rawText.length > cleanedText.length * 1.3)
        val manifest = store.writeChunk(
            store.createManifest("cleaned-duration", 1),
            0,
            GeneratedAudio(FloatArray(168_000), 24_000),
            rawText
        )

        val report = BatchValidator.validate(store, manifest)

        assertTrue(report.passed)
        assertTrue(cleanedText.length >= 100)
        assertTrue(report.findings.none { it.code == "AUDIO_TOO_SHORT" })
        assertTrue(report.findings.any { it.code == "AUDIO_CAP_INPUTS_UNAVAILABLE" })
    }

    @Test
    fun asrValidationUsesCleanedSynthesisTextForExpectedEdges() {
        val root = Files.createTempDirectory("batch-cleaned-asr")
        val store = BatchAudioStore(root)
        val rawText = "  Alpha   beta   gamma   delta   epsilon.  "
        val cleanedText = TextBatching.cleanForSynthesis(rawText)
        val manifest = store.writeChunk(
            store.createManifest("cleaned-asr", 1),
            0,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            rawText
        )

        val findings = BatchAsrValidator.validate(store, manifest, BatchAsrTranscriber { _, _ -> cleanedText })

        assertTrue(findings.all { it.passed })
        assertEquals(
            BatchAsrValidator.expectedSegment(cleanedText, AsrWindow.PREFIX),
            findings.single { it.window == AsrWindow.PREFIX }.expectedText
        )
    }

    @Test
    fun explicitPerChunkCapFailsClosedForExactAggregateBoundary() {
        val root = Files.createTempDirectory("batch-cap-reached")
        val store = BatchAudioStore(root)
        val capTokens = 2
        val capSamples = capTokens * 1_920
        val metadata = mapOf(BatchValidationMetadata.audioCapTokensKey(0) to capTokens.toString())
        val manifest = store.writeChunk(
            store.createManifest("cap-reached", 1, metadata),
            0,
            GeneratedAudio(FloatArray(capSamples), 24_000),
            "Aggregate cap boundary."
        )

        val report = BatchValidator.validate(store, manifest)

        val finding = report.findings.single { it.code == "AUDIO_CAP_REACHED" }
        assertEquals(ValidationSeverity.ERROR, finding.severity)
        assertEquals(0, finding.chunkIndex)
        assertTrue(finding.message.contains("$capTokens tokens"))
        assertTrue(finding.message.contains("$capSamples samples"))
        assertTrue(report.findings.none { it.code == "AUDIO_AGGREGATE_CAP_EXCEEDED" })
        assertFalse(report.passed)
    }

    @Test
    fun explicitPerChunkCapReportsAggregateOverflowAsSeparateMachineReadableError() {
        val root = Files.createTempDirectory("batch-cap-overflow")
        val store = BatchAudioStore(root)
        val capTokens = 2
        val capSamples = capTokens * 1_920
        val manifest = store.writeChunk(
            store.createManifest("cap-overflow", 1),
            0,
            GeneratedAudio(FloatArray(capSamples + 1), 24_000),
            "Aggregate retry output."
        )

        val report = BatchValidator.validate(
            store,
            manifest,
            capInputs = mapOf(0 to BatchValidationCapInput(capTokens))
        )

        assertEquals(ValidationSeverity.ERROR, report.findings.single { it.code == "AUDIO_CAP_REACHED" }.severity)
        val overflow = report.findings.single { it.code == "AUDIO_AGGREGATE_CAP_EXCEEDED" }
        assertEquals(ValidationSeverity.ERROR, overflow.severity)
        assertEquals(0, overflow.chunkIndex)
        assertTrue(overflow.message.contains("observed samples=${capSamples + 1}"))
        assertTrue(overflow.message.contains("cap samples=$capSamples"))
        assertFalse(report.passed)
    }

    @Test
    fun malformedCapMetadataFailsClosedInsteadOfGuessing() {
        val root = Files.createTempDirectory("batch-cap-invalid")
        val store = BatchAudioStore(root)
        val metadata = mapOf(BatchValidationMetadata.audioCapTokensKey(0) to "not-a-number")
        val manifest = store.writeChunk(
            store.createManifest("cap-invalid", 1, metadata),
            0,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            "Invalid aggregate cap metadata."
        )

        val report = BatchValidator.validate(store, manifest)

        val finding = report.findings.single { it.code == "AUDIO_CAP_INPUTS_INVALID" }
        assertEquals(ValidationSeverity.ERROR, finding.severity)
        assertEquals(0, finding.chunkIndex)
        assertTrue(finding.message.contains("positive integer"))
        assertFalse(report.passed)
    }

    @Test
    fun deterministicValidationFlagsAudioThatIsTooShortForItsText() {
        val root = Files.createTempDirectory("batch-audio-too-short")
        val store = BatchAudioStore(root)
        val text = "A deliberately long chunk of text that cannot be represented by a single sample. " +
            "The validator must identify this as an implausibly short audio result."
        val manifest = store.writeChunk(
            store.createManifest("audio-too-short", 1),
            0,
            GeneratedAudio(FloatArray(24_000), 24_000),
            text
        )

        val report = BatchValidator.validate(store, manifest)

        assertTrue(report.errors.any { it.code == "AUDIO_TOO_SHORT" })
        assertTrue(report.errors.single { it.code == "AUDIO_TOO_SHORT" }.message.contains("bytes"))
    }

    private fun completeManifest(store: BatchAudioStore, batchId: String, text: String): BatchManifest =
        store.writeChunk(
            store.createManifest(batchId, 1),
            0,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            text
        )

    private fun closeForTest(viewModel: StudioViewModel) {
        ViewModelStore().also { store ->
            store.put("test", viewModel)
            store.clear()
        }
    }
}
