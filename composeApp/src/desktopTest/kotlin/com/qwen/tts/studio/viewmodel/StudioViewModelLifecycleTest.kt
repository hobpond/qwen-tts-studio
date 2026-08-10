package com.qwen.tts.studio.viewmodel

import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.BatchGenerationRequest
import com.qwen.tts.studio.batch.BatchIdentity
import com.qwen.tts.studio.batch.BatchVoiceMode
import com.qwen.tts.studio.engine.NativeBackendPreference
import androidx.lifecycle.ViewModelStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioViewModelLifecycleTest {
    @Test
    fun capturedVoiceManifestReplaySuppressesDesignInstruction() {
        val root = Files.createTempDirectory("studio-viewmodel-replay")
        val manifest = BatchAudioStore(root).generateManifestFromTexts(
            batchId = "captured",
            texts = listOf("A captured voice chunk."),
            metadata = mapOf(
                "modelDir" to root.toString(),
                "modelName" to "voicedesign.gguf",
                "backendPreference" to "cpu",
                "voiceMode" to "captured-voice",
                "instruction" to "Original design instruction",
                "voiceProvenance" to "Original design instruction"
            )
        )

        val viewModel = StudioViewModel()
        val request = viewModel.loadBatchManifest(root.resolve("manifest.json").toFile())

        assertEquals(manifest.chunks.map { it.text }, request.texts)
        assertEquals(null, request.instruction)
        assertEquals("Original design instruction", request.voiceProvenance)
    }

    @Test
    fun ordinaryManifestReplayPreservesInstruction() {
        val root = Files.createTempDirectory("studio-viewmodel-replay")
        BatchAudioStore(root).generateManifestFromTexts(
            batchId = "ordinary",
            texts = listOf("An ordinary chunk."),
            metadata = mapOf(
                "modelDir" to root.toString(),
                "backendPreference" to "cpu",
                "voiceMode" to "model-default",
                "instruction" to "Read this calmly"
            )
        )

        val viewModel = StudioViewModel()
        assertEquals("Read this calmly", viewModel.loadBatchManifest(root.resolve("manifest.json").toFile()).instruction)
    }

    @Test
    fun voiceDesignSnapshotWithoutConditioningArtifactIsRejected() {
        val snapshot = ReusableVoiceSnapshot(
            speakerEmbeddingPath = null,
            referenceWavPath = "preview.wav",
            modelDir = "models",
            modelName = "voice-design.gguf",
            backend = com.qwen.tts.studio.engine.NativeBackendPreference.Cpu,
            embeddingDim = 0,
            sourceText = "preview",
            sourceInstruction = "warm"
        )

        assertTrue(reusableVoiceValidationError(snapshot).orEmpty().contains("conditioning artifact"))
        assertEquals(null, reusableVoiceValidationError(null))
    }

    @Test
    fun studioGeneratedManifestUsesCanonicalIdentityForReplay() {
        val root = Files.createTempDirectory("studio-generated-manifest")
        val viewModel = StudioViewModel()
        try {
            val request = viewModel.generateBatchManifest(
                outputDirectory = root.toFile(),
                texts = listOf("A generated manifest chunk."),
                modelDir = root.toString(),
                modelName = "model.gguf",
                backendPreference = NativeBackendPreference.Cpu,
                speakerEmbeddingPath = null,
                iclPromptPath = null
            )
            val manifest = BatchAudioStore(root).loadManifest()
            val replay = viewModel.loadBatchManifest(root.resolve("manifest.json").toFile())

            assertEquals(request.texts, manifest.chunks.map { it.text })
            assertEquals("model-default", manifest.metadata["voiceMode"])
            assertEquals("1", manifest.metadata["channels"])
            assertEquals("PCM_SIGNED_LE_16", manifest.metadata["pcmEncoding"])
            assertEquals(manifest.metadata["textFingerprint"], BatchIdentity.forRequest(replay).metadata()["textFingerprint"])
            assertEquals(manifest.metadata, BatchIdentity.forRequest(replay).metadata())
        } finally {
            viewModel.clearForTest()
        }
    }

    @Test
    fun capturedArtifactsSurviveActiveSnapshotClearAndManifestReload() {
        val root = Files.createTempDirectory("studio-captured-artifact")
        val embedding = root.resolve("embedding.json").toFile().apply { writeText("durable embedding") }
        val reference = root.resolve("reference.wav").toFile().apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val snapshot = ReusableVoiceSnapshot(
            speakerEmbeddingPath = embedding.absolutePath,
            speakerEmbeddingSha256 = BatchIdentity.sha256File(embedding),
            referenceWavPath = reference.absolutePath,
            referenceWavSha256 = BatchIdentity.sha256File(reference),
            modelDir = root.toString(),
            modelName = "model.gguf",
            backend = NativeBackendPreference.Cpu,
            embeddingDim = 4,
            sourceText = "preview",
            sourceInstruction = "warm"
        )
        val viewModel = StudioViewModel()
        try {
            viewModel.installReusableVoiceSnapshotForTest(snapshot)
            viewModel.clearCapturedVoice()
            assertTrue(embedding.isFile)
            assertTrue(reference.isFile)

            val request = BatchGenerationRequest(
                batchId = "durable-captured",
                modelDir = root.toString(),
                modelName = "model.gguf",
                backendPreference = NativeBackendPreference.Cpu,
                texts = listOf("captured replay"),
                languageId = 2050,
                instruction = "warm",
                speaker = null,
                speakerEmbeddingPath = embedding.absolutePath,
                iclPromptPath = null,
                outputDirectory = root,
                voiceProvenance = snapshot.sourceInstruction,
                voiceMode = BatchVoiceMode.CAPTURED_VOICE,
                speakerEmbeddingSha256 = snapshot.speakerEmbeddingSha256,
                referenceWavPath = reference.absolutePath,
                referenceWavSha256 = snapshot.referenceWavSha256
            )
            BatchAudioStore(root).generateManifestFromTexts(
                batchId = request.batchId,
                texts = request.texts,
                metadata = BatchIdentity.forRequest(request).metadata()
            )

            val restarted = StudioViewModel()
            try {
                val replay = restarted.loadBatchManifest(root.resolve("manifest.json").toFile())
                assertEquals(BatchVoiceMode.CAPTURED_VOICE, replay.voiceMode)
                assertEquals(embedding.absolutePath, replay.speakerEmbeddingPath)
                assertEquals(snapshot.speakerEmbeddingSha256, replay.speakerEmbeddingSha256)
                assertEquals(null, replay.instruction)
            } finally {
                restarted.clearForTest()
            }
        } finally {
            viewModel.clearForTest()
        }
    }

}

private fun StudioViewModel.clearForTest() {
    ViewModelStore().also { store ->
        store.put("test", this)
        store.clear()
    }
}
