package com.qwen.tts.studio.agent

import androidx.lifecycle.ViewModelStore
import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.BatchEngine
import com.qwen.tts.studio.batch.BatchGenerationRequest
import com.qwen.tts.studio.batch.BatchGenerationStatus
import com.qwen.tts.studio.batch.BatchIdentity
import com.qwen.tts.studio.batch.BatchVoiceMode
import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenEngine
import com.qwen.tts.studio.viewmodel.StudioViewModel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioUiInstrumentationTest {
    @Test
    fun instrumentationReplaysGenerationResumeRegenerationAndValidationActions() = runBlocking {
        val root = Files.createTempDirectory("studio-ui-instrumentation")
        val request = request(root)
        val actions = mutableListOf<AgentUiActionEvidence>()
        val viewModel = StudioViewModel { _ -> DeterministicBatchEngine() }
        try {
            val ui = StudioUiInstrumentation(viewModel) { actions += it }
            ui.generate(request)
            val manifestFile = request.outputDirectory.resolve("manifest.json").toFile()
            ui.loadManifest(manifestFile)
            ui.validateAll(manifestFile, null, null)
            ui.resume()
            ui.regenerateAll()
            ui.validateAll(manifestFile, null, null)
            ui.regenerateChunk(0)
            ui.validateChunk(manifestFile, 0, null, null)

            assertEquals(
                listOf(
                    "generate",
                    "load-manifest",
                    "validate-all",
                    "resume",
                    "regenerate-all",
                    "validate-all",
                    "regenerate-chunk",
                    "validate-chunk"
                ),
                actions.map { it.action }
            )
            assertTrue(actions.all { it.completed }, actions.joinToString())
            assertEquals(BatchGenerationStatus.COMPLETED, viewModel.batchState.value.result?.status)
            assertTrue(viewModel.batchState.value.manifest?.chunks?.all { it.validationPassed == true } == true)
        } finally {
            closeForTest(viewModel)
        }
    }

    @Test
    fun failedValidationIsRecordedAsAnIncompleteUiAction() = runBlocking {
        val root = Files.createTempDirectory("studio-ui-validation-failure")
        val request = request(root, listOf("Pending chunk."))
        BatchAudioStore(request.outputDirectory).generateManifestFromTexts(
            request.batchId,
            request.texts,
            BatchIdentity.forRequest(request).metadata()
        )
        val actions = mutableListOf<AgentUiActionEvidence>()
        val viewModel = StudioViewModel()
        try {
            val ui = StudioUiInstrumentation(viewModel) { actions += it }
            val manifestFile = request.outputDirectory.resolve("manifest.json").toFile()
            ui.loadManifest(manifestFile)

            assertFailsWith<IllegalArgumentException> {
                ui.validateAll(manifestFile, null, null)
            }

            val evidence = actions.single { it.action == "validate-all" }
            assertFalse(evidence.completed)
            assertTrue(evidence.error.orEmpty().contains("generate or resume"))
            assertEquals(false, viewModel.batchState.value.chunkValidation[0]?.passed)
        } finally {
            closeForTest(viewModel)
        }
    }

    @Test
    fun instrumentationRechunksFailedManifestChildrenThenResumesValidatesAndCombines() = runBlocking {
        val root = Files.createTempDirectory("studio-ui-rechunk")
        val failedText = "Failed chunk sentence one. Failed chunk sentence two. Failed chunk sentence three."
        val request = request(root, listOf("complete head", failedText, "complete tail"))
        val viewModel = StudioViewModel { _ -> DeterministicBatchEngine() }
        val actions = mutableListOf<AgentUiActionEvidence>()
        try {
            val ui = StudioUiInstrumentation(viewModel) { actions += it }
            ui.generate(request)
            val manifestFile = request.outputDirectory.resolve("manifest.json").toFile()
            val store = BatchAudioStore(request.outputDirectory)
            store.markFailed(store.loadManifest(), 1, "ASR suffix mismatch")

            ui.loadManifest(manifestFile)
            val rechunked = ui.rechunkFailed(manifestFile, maxCharacters = 20)
            ui.resume()
            ui.validateAll(manifestFile, null, null)
            ui.combine(manifestFile, request.outputDirectory.resolve("combined.wav").toFile())

            assertTrue(rechunked.chunks.any { it.displayIndex == "1.1" })
            assertTrue(rechunked.chunks.any { it.displayIndex == "1.2" })
            assertTrue(actions.all { it.completed }, actions.joinToString())
            assertEquals(
                listOf("generate", "load-manifest", "rechunk-failed", "resume", "validate-all", "combine"),
                actions.map { it.action }
            )
            assertTrue(viewModel.batchState.value.manifest?.chunks?.all { it.validationPassed == true } == true)
            assertTrue(Files.isRegularFile(request.outputDirectory.resolve("combined.wav")))
        } finally {
            closeForTest(viewModel)
        }
    }

    @Test
    fun cancellationRecordsCancelledResultAndManifestState() = runBlocking {
        val root = Files.createTempDirectory("studio-ui-cancellation")
        val actions = mutableListOf<AgentUiActionEvidence>()
        val viewModel = StudioViewModel { _ -> DeterministicBatchEngine(delayMillis = 75) }
        try {
            val ui = StudioUiInstrumentation(viewModel) { actions += it }
            ui.cancel(request(root))

            assertEquals(listOf("cancel"), actions.map { it.action })
            assertTrue(actions.single().completed)
            assertEquals(BatchGenerationStatus.CANCELLED, viewModel.batchState.value.result?.status)
            assertTrue(viewModel.batchState.value.manifest?.chunks?.any { it.status.name == "CANCELLED" } == true)
        } finally {
            closeForTest(viewModel)
        }
    }

    private fun request(root: Path, texts: List<String> = listOf(
        "First instrumented batch chunk.",
        "Second instrumented batch chunk."
    )): BatchGenerationRequest {
        val output = root.resolve("batch")
        Files.createDirectories(output)
        return BatchGenerationRequest(
            batchId = "instrumented-batch",
            modelDir = root.resolve("model").toString(),
            modelName = "test.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = texts,
            languageId = QwenEngine.mapLanguageToId("English"),
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = output,
            voiceMode = BatchVoiceMode.MODEL_DEFAULT
        )
    }

    private fun closeForTest(viewModel: StudioViewModel) {
        ViewModelStore().also { store ->
            store.put("test", viewModel)
            store.clear()
        }
    }

    private class DeterministicBatchEngine(
        private val delayMillis: Long = 0
    ) : BatchEngine {
        override fun loadDetailed(
            modelDir: String,
            modelName: String?,
            backendPreference: NativeBackendPreference
        ) = QwenEngine.NativeOperationResult(success = true)

        override fun supportsReusableBufferedSession() = true

        override fun generateDetailed(
            text: String,
            speakerEmbeddingPath: String?,
            iclPromptPath: String?,
            languageId: Int,
            instruction: String?,
            speaker: String?,
            maxAudioTokens: Int
        ): QwenEngine.NativeResult {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val sampleCount = maxOf(8_000, text.length * 800)
            val samples = FloatArray(sampleCount) { index ->
                if (index % 80 < 40) 0.04f else -0.04f
            }
            return QwenEngine.NativeResult(samples, 24_000, true, null, 1L)
        }
    }
}
