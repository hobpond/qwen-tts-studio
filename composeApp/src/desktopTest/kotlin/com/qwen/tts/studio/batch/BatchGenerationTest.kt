package com.qwen.tts.studio.batch

import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenEngine
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchGenerationTest {
    @Test
    fun loadsOnceAndGeneratesSequentialChunksThroughReusableEngine() {
        val root = Files.createTempDirectory("batch-generation")
        val fake = RecordingBatchEngine()
        val firstText = "a".repeat(900)
        val secondText = "b".repeat(900)
        val request = BatchGenerationRequest(
            batchId = "recording",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf(firstText, secondText),
            languageId = 2050,
            instruction = null,
            speaker = "speaker-1",
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)

        assertEquals(BatchGenerationStatus.COMPLETED, result.status)
        assertEquals(1, fake.loadCalls)
        assertEquals(listOf(firstText, "\n\n$secondText"), fake.generatedTexts)
        assertEquals(listOf(firstText, "\n\n$secondText"), result.manifest.chunks.map { it.text })
        assertTrue(Files.exists(root.resolve("manifest.json")))
        assertTrue(Files.exists(BatchAudioStore(root).recombine(result.manifest, root.resolve("combined.wav"))))
    }

    @Test
    fun cancellationRequestedAfterFinalChunkStillCompletesValidBatch() {
        val root = Files.createTempDirectory("batch-generation")
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "final-cancel",
            modelDir = root.toString(),
            modelName = null,
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("only"),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )
        var cancellationRequested = false

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(
            request,
            shouldCancel = { cancellationRequested },
            onProgress = { _, _ -> cancellationRequested = true }
        )

        assertEquals(BatchGenerationStatus.COMPLETED, result.status)
        assertEquals(BatchChunkStatus.COMPLETE, result.manifest.chunks.single().status)
    }

    @Test
    fun capturedVoiceUsesArtifactForEveryChunkAndKeepsInstructionAsProvenance() {
        val root = Files.createTempDirectory("batch-captured-voice")
        val fake = RecordingBatchEngine()
        val firstText = "a".repeat(900)
        val secondText = "b".repeat(900)
        val request = BatchGenerationRequest(
            batchId = "captured-voice",
            modelDir = root.toString(),
            modelName = "voicedesign.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf(firstText, secondText),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = root.resolve("captured.json").toString(),
            iclPromptPath = null,
            outputDirectory = root,
            voiceProvenance = "Warm narrator with calm delivery."
        )

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)

        assertEquals(BatchGenerationStatus.COMPLETED, result.status)
        assertEquals(
            listOf<String?>(root.resolve("captured.json").toString(), root.resolve("captured.json").toString()),
            fake.embeddingPaths
        )
        assertTrue(fake.instructions.all { it == null })
        assertEquals("Warm narrator with calm delivery.", result.manifest.metadata["voiceProvenance"])
    }

    @Test
    fun resumesCompletedChunkWithoutGeneratingItAgain() {
        val root = Files.createTempDirectory("batch-generation-resume")
        val fake = RecordingBatchEngine()
        val firstText = "a".repeat(900)
        val secondText = "b".repeat(900)
        val request = BatchGenerationRequest(
            batchId = "resume",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf(firstText, secondText),
            languageId = 2050,
            instruction = null,
            speaker = "speaker-1",
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        val first = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)
        assertEquals(BatchGenerationStatus.COMPLETED, first.status)
        fake.generatedTexts.clear()

        val resumed = BatchGenerationSession(fake, BatchAudioStore(root)).run(request.copy(batchId = "new-ui-run-id"))

        assertEquals(BatchGenerationStatus.COMPLETED, resumed.status)
        assertEquals(emptyList(), fake.generatedTexts)
        assertEquals(2, resumed.items.size)
    }

    @Test
    fun regeneratesOnlyRequestedCompletedChunks() {
        val root = Files.createTempDirectory("batch-generation-regenerate")
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "regenerate",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("a".repeat(900), "b".repeat(900)),
            languageId = 2050,
            instruction = null,
            speaker = "speaker-1",
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        BatchGenerationSession(fake, BatchAudioStore(root)).run(request)
        fake.generatedTexts.clear()

        val regenerated = BatchGenerationSession(fake, BatchAudioStore(root)).run(
            request.copy(regenerateIndices = setOf(0))
        )

        assertEquals(BatchGenerationStatus.COMPLETED, regenerated.status)
        assertEquals(listOf("a".repeat(900)), fake.generatedTexts)
    }

    @Test
    fun singleChunkActionDoesNotContinueIntoOtherPendingChunks() {
        val root = Files.createTempDirectory("batch-generation-single")
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "single",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("a".repeat(900), "b".repeat(900)),
            languageId = 2050,
            instruction = null,
            speaker = "speaker-1",
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root,
            onlyIndices = setOf(0),
            regenerateIndices = setOf(0)
        )

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)

        assertEquals(BatchGenerationStatus.PARTIAL, result.status)
        assertEquals(listOf("a".repeat(900)), fake.generatedTexts)
        assertEquals(BatchChunkStatus.PENDING, result.manifest.chunks.first { it.index == 1 }.status)
    }

    private class RecordingBatchEngine : BatchEngine {
        var loadCalls = 0
        val generatedTexts = mutableListOf<String>()
        val embeddingPaths = mutableListOf<String?>()
        val instructions = mutableListOf<String?>()

        override fun loadDetailed(
            modelDir: String,
            modelName: String?,
            backendPreference: NativeBackendPreference
        ) = QwenEngine.NativeOperationResult(success = true)
            .also { loadCalls++ }

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
            generatedTexts += text
            embeddingPaths += speakerEmbeddingPath
            instructions += instruction
            return QwenEngine.NativeResult(
                audio = floatArrayOf(0f, 0.1f),
                sampleRate = 24_000,
                success = true,
                errorMsg = null,
                timeMs = 1L
            )
        }
    }
}
