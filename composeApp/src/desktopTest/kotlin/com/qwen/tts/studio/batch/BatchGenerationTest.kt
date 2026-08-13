package com.qwen.tts.studio.batch

import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenEngine
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        assertEquals(listOf(firstText, secondText), fake.generatedTexts)
        assertEquals(listOf(firstText, secondText), result.manifest.chunks.map { it.text })
        assertTrue(Files.exists(root.resolve("manifest.json")))
        assertTrue(Files.exists(BatchAudioStore(root).recombine(result.manifest, root.resolve("combined.wav"))))
    }

    @Test
    fun reportsPersistedManifestAfterEachChunkSoUiCanShowCompletion() {
        val root = Files.createTempDirectory("batch-manifest-progress")
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "manifest-progress",
            modelDir = root.toString(),
            modelName = null,
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("first", "second"),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )
        val snapshots = mutableListOf<BatchManifest>()

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(
            request,
            onManifest = { snapshots += it }
        )

        val completedCounts = snapshots.map { manifest ->
            manifest.chunks.count { it.status == BatchChunkStatus.COMPLETE }
        }.filter { it > 0 }
        assertTrue(completedCounts.contains(1), "first persisted completion was not reported")
        assertTrue(completedCounts.contains(2), "final persisted completion was not reported")
        assertEquals(BatchChunkStatus.COMPLETE, result.manifest.chunks.last().status)
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
        val capturedArtifact = root.resolve("captured.json").toFile().apply {
            writeText("captured speaker artifact")
        }
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
            speakerEmbeddingPath = capturedArtifact.absolutePath,
            iclPromptPath = null,
            outputDirectory = root,
            voiceProvenance = "Warm narrator with calm delivery.",
            voiceMode = BatchVoiceMode.CAPTURED_VOICE,
            speakerEmbeddingSha256 = BatchIdentity.sha256File(capturedArtifact)
        )

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)

        assertEquals(BatchGenerationStatus.COMPLETED, result.status)
        assertEquals(
            listOf<String?>(capturedArtifact.absolutePath, capturedArtifact.absolutePath),
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

    @Test
    fun directGenerationUsesTheSameFinalPlanAsManifestGeneration() {
        val root = Files.createTempDirectory("batch-plan-consistency")
        val source = "first paragraph\n\nsecond paragraph\n\nthird paragraph"
        val plan = TextBatching.packParagraphs(source, 20)
        val store = BatchAudioStore(root)
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "planned",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = plan,
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )
        val generatedManifest = store.generateManifestFromTexts(
            "planned",
            plan,
            BatchIdentity.forRequest(request).metadata()
        )

        val result = BatchGenerationSession(fake, store).run(request)

        assertEquals(plan, generatedManifest.chunks.map { it.text })
        assertEquals(plan.map(TextBatching::cleanForSynthesis), fake.generatedTexts)
        assertEquals(plan, result.manifest.chunks.map { it.text })
        assertEquals(source, result.manifest.chunks.joinToString(separator = "") { it.text })
    }

    @Test
    fun pendingChunkWriteFailureIsReportedAndCannotRemainComplete() {
        val root = Files.createTempDirectory("batch-persistence-failure")
        Files.createDirectory(root.resolve("chunk-000000.wav"))
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "write-failure",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("source text"),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)
        val persisted = BatchAudioStore(root).loadManifest()

        assertEquals(BatchGenerationStatus.FAILED, result.status)
        assertEquals(BatchChunkStatus.FAILED, result.manifest.chunks.single().status)
        assertEquals(BatchChunkStatus.FAILED, persisted.chunks.single().status)
        assertTrue(result.error.orEmpty().isNotBlank())
    }

    @Test
    fun newOversizedTextIsLosslesslyReplannedToSafeChunks() {
        val root = Files.createTempDirectory("batch-adaptive-replan")
        val fake = RecordingBatchEngine().apply {
            memory = QwenEngine.BackendMemory(2L * 1024 * 1024 * 1024, 12L * 1024 * 1024 * 1024)
        }
        val source = "a".repeat(6_000)
        val request = BatchGenerationRequest(
            batchId = "adaptive-replan",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf(source),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)
        val limit = BatchMemoryPolicy.maxCharacters(BatchMemorySnapshot(fake.memory, null, null))

        assertEquals(BatchGenerationStatus.COMPLETED, result.status)
        assertTrue(fake.generatedTexts.all { it.length <= limit })
        assertEquals(source, fake.generatedTexts.joinToString(separator = ""))
    }

    @Test
    fun insufficientMemoryPreservesCompatibleMixedStatusManifest() {
        val root = Files.createTempDirectory("batch-replay-memory")
        val store = BatchAudioStore(root)
        val fake = RecordingBatchEngine()
        val texts = listOf("complete", "pending", "failed", "cancelled")
        val request = BatchGenerationRequest(
            batchId = "mixed-status",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = texts,
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        BatchGenerationSession(fake, store).run(request.copy(onlyIndices = setOf(0)))
        var mixed = store.loadManifest()
        mixed = store.markFailed(mixed, 2, "seeded failure")
        mixed = store.markCancelled(mixed, 3)
        val before = store.loadManifest()

        fake.memory = QwenEngine.BackendMemory(
            1L * 1024 * 1024,
            12L * 1024 * 1024 * 1024
        )
        val result = BatchGenerationSession(fake, store).run(request.copy(batchId = "replayed"))

        assertEquals(BatchGenerationStatus.FAILED, result.status)
        assertEquals(
            listOf(
                BatchChunkStatus.COMPLETE,
                BatchChunkStatus.PENDING,
                BatchChunkStatus.FAILED,
                BatchChunkStatus.CANCELLED
            ),
            before.chunks.map { it.status }
        )
        assertEquals(before, result.manifest)
        assertEquals(before, store.loadManifest())
        assertTrue(fake.generatedTexts.isNotEmpty())
    }

    @Test
    fun selectiveRegenerationLeavesNonSelectedChunkStateAndMetadataUntouched() {
        val root = Files.createTempDirectory("batch-replay-selective")
        val store = BatchAudioStore(root)
        val fake = RecordingBatchEngine()
        val texts = listOf("zero", "one", "two")
        val request = BatchGenerationRequest(
            batchId = "selective-replay",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = texts,
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        BatchGenerationSession(fake, store).run(request)
        val before = store.loadManifest()
        fake.generatedTexts.clear()

        val result = BatchGenerationSession(fake, store).run(
            request.copy(regenerateIndices = setOf(0), batchId = "regenerated")
        )

        assertEquals(BatchGenerationStatus.COMPLETED, result.status)
        assertEquals(listOf("zero"), fake.generatedTexts)
        assertEquals(before.metadata, result.manifest.metadata)
        assertEquals(
            before.chunks.filter { it.index != 0 },
            result.manifest.chunks.filter { it.index != 0 }
        )
    }

    @Test
    fun restoredNormalMemorySkipsValidCompletedWavsAfterInsufficientMemory() {
        val root = Files.createTempDirectory("batch-replay-restored-memory")
        val store = BatchAudioStore(root)
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "restored-memory",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("first", "second"),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        val completed = BatchGenerationSession(fake, store).run(request)
        val before = store.loadManifest()
        fake.generatedTexts.clear()
        fake.memory = QwenEngine.BackendMemory(
            1L * 1024 * 1024,
            12L * 1024 * 1024 * 1024
        )

        val insufficient = BatchGenerationSession(fake, store).run(request.copy(batchId = "blocked"))
        assertEquals(BatchGenerationStatus.FAILED, insufficient.status)
        assertEquals(before, store.loadManifest())

        fake.memory = null
        val resumed = BatchGenerationSession(fake, store).run(request.copy(batchId = "resumed"))

        assertEquals(BatchGenerationStatus.COMPLETED, completed.status)
        assertEquals(BatchGenerationStatus.COMPLETED, resumed.status)
        assertTrue(fake.generatedTexts.isEmpty())
        assertEquals(before.chunks, resumed.manifest.chunks)
        assertEquals(before.metadata, resumed.manifest.metadata)
    }

    @Test
    fun incompatibleReplayRequestDoesNotAdoptExistingManifestUnderInsufficientMemory() {
        val root = Files.createTempDirectory("batch-replay-incompatible")
        val store = BatchAudioStore(root)
        val fake = RecordingBatchEngine()
        val originalTexts = listOf("original one", "original two")
        val request = BatchGenerationRequest(
            batchId = "original",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = originalTexts,
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        BatchGenerationSession(fake, store).run(request)
        val before = store.loadManifest()
        fake.memory = QwenEngine.BackendMemory(
            1L * 1024 * 1024,
            12L * 1024 * 1024 * 1024
        )
        val replacementTexts = listOf("replacement one", "replacement two")

        val result = BatchGenerationSession(fake, store).run(
            request.copy(batchId = "replacement", texts = replacementTexts)
        )

        assertEquals(BatchGenerationStatus.FAILED, result.status)
        assertEquals(replacementTexts, result.manifest.chunks.map { it.text })
        assertEquals(before, store.loadManifest())
        assertTrue(fake.generatedTexts.isNotEmpty())
    }

    @Test
    fun boundaryPreservingOversizedReplayLeavesManifestSidecarsAndStatusesUnchanged() {
        val root = Files.createTempDirectory("batch-boundary-replay")
        val store = BatchAudioStore(root)
        val fake = RecordingBatchEngine().apply {
            memory = memoryThatCapsAt(800)
        }
        val texts = listOf("a".repeat(1_200), "short")
        val request = BatchGenerationRequest(
            batchId = "boundary-replay",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = texts,
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root,
            preserveChunkBoundaries = true,
            onlyIndices = setOf(0)
        )
        assertEquals(
            800,
            BatchMemoryPolicy.plan(BatchMemorySnapshot(fake.memory, null, null)).maxCharacters
        )

        var before = store.generateManifestFromTexts(
            "boundary-replay",
            texts,
            BatchIdentity.forRequest(request).metadata() + ("batchMaxCharacters" to "5000")
        )
        before = store.markFailed(before, 1, "seeded failure")
        val manifestBytes = Files.readAllBytes(root.resolve("manifest.json")).toList()
        val sidecars = texts.indices.associateWith { index ->
            Files.readAllBytes(root.resolve("chunk-%06d.txt".format(index))).toList()
        }

        val result = BatchGenerationSession(fake, store).run(request.copy(batchId = "replay"))

        assertEquals(BatchGenerationStatus.FAILED, result.status)
        assertEquals(before, result.manifest)
        assertEquals(before, store.loadManifest())
        assertEquals(manifestBytes, Files.readAllBytes(root.resolve("manifest.json")).toList())
        texts.indices.forEach { index ->
            assertEquals(sidecars[index], Files.readAllBytes(root.resolve("chunk-%06d.txt".format(index))).toList())
        }
        assertTrue(fake.generatedTexts.isEmpty())
    }

    @Test
    fun tamperedConditioningArtifactsPreventNativeWorkAndPreserveManifest() {
        listOf("embedding", "icl", "reference").forEach { kind ->
            val root = Files.createTempDirectory("batch-tampered-$kind")
            val artifact = root.resolve("$kind.bin").toFile().apply { writeText("original") }
            val base = BatchGenerationRequest(
                batchId = "tampered-$kind",
                modelDir = root.toString(),
                modelName = "model.gguf",
                backendPreference = NativeBackendPreference.Cpu,
                texts = listOf("conditioned text"),
                languageId = 2050,
                instruction = null,
                speaker = null,
                speakerEmbeddingPath = null,
                iclPromptPath = null,
                outputDirectory = root
            )
            val checksum = BatchIdentity.sha256File(artifact)
            val request = when (kind) {
                "embedding" -> base.copy(
                    voiceMode = BatchVoiceMode.SPEAKER_EMBEDDING,
                    speakerEmbeddingPath = artifact.absolutePath,
                    speakerEmbeddingSha256 = checksum
                )
                "icl" -> base.copy(
                    voiceMode = BatchVoiceMode.ICL_PROMPT,
                    iclPromptPath = artifact.absolutePath,
                    iclPromptSha256 = checksum
                )
                else -> base.copy(
                    referenceWavPath = artifact.absolutePath,
                    referenceWavSha256 = checksum
                )
            }
            val fake = RecordingBatchEngine()
            val store = BatchAudioStore(root)
            val first = BatchGenerationSession(fake, store).run(request)
            assertEquals(BatchGenerationStatus.COMPLETED, first.status)
            val manifestBytes = Files.readAllBytes(root.resolve("manifest.json")).toList()
            val sidecarBytes = Files.readAllBytes(root.resolve("chunk-000000.txt")).toList()
            val loadCallsBeforeTamper = fake.loadCalls
            fake.generatedTexts.clear()
            artifact.appendText("tampered")

            val result = BatchGenerationSession(fake, store).run(request.copy(batchId = "retry-$kind"))

            assertEquals(BatchGenerationStatus.FAILED, result.status, kind)
            assertEquals(loadCallsBeforeTamper, fake.loadCalls, kind)
            assertTrue(fake.generatedTexts.isEmpty(), kind)
            assertEquals(manifestBytes, Files.readAllBytes(root.resolve("manifest.json")).toList(), kind)
            assertEquals(sidecarBytes, Files.readAllBytes(root.resolve("chunk-000000.txt")).toList(), kind)
        }
    }

    @Test
    fun incompatibleDirectStartPreservesExistingBatchUntilReplacementIsAuthorized() {
        val root = Files.createTempDirectory("batch-authorized-replacement")
        val store = BatchAudioStore(root)
        val fake = RecordingBatchEngine()
        val original = BatchGenerationRequest(
            batchId = "original",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("original text"),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )
        assertEquals(
            BatchGenerationStatus.COMPLETED,
            BatchGenerationSession(fake, store).run(original).status
        )
        val originalManifestBytes = Files.readAllBytes(root.resolve("manifest.json")).toList()
        val originalSidecarBytes = Files.readAllBytes(root.resolve("chunk-000000.txt")).toList()
        val originalWavBytes = Files.readAllBytes(root.resolve("chunk-000000.wav")).toList()
        fake.generatedTexts.clear()
        val replacement = original.copy(batchId = "replacement", texts = listOf("replacement text"))

        val rejected = BatchGenerationSession(fake, store).run(replacement)

        assertEquals(BatchGenerationStatus.FAILED, rejected.status)
        assertTrue(rejected.error.orEmpty().contains("incompatible"))
        assertTrue(fake.generatedTexts.isEmpty())
        assertEquals(originalManifestBytes, Files.readAllBytes(root.resolve("manifest.json")).toList())
        assertEquals(originalSidecarBytes, Files.readAllBytes(root.resolve("chunk-000000.txt")).toList())
        assertEquals(originalWavBytes, Files.readAllBytes(root.resolve("chunk-000000.wav")).toList())

        val authorized = BatchGenerationSession(fake, store).run(
            replacement.copy(allowIncompatibleReplacement = true)
        )

        assertEquals(BatchGenerationStatus.COMPLETED, authorized.status)
        assertEquals(listOf("replacement text"), authorized.manifest.chunks.map { it.text })
        assertEquals("replacement text", Files.readString(root.resolve("chunk-000000.txt")))
        assertTrue(fake.generatedTexts.contains("replacement text"))
    }

    @Test
    fun corruptManifestBlocksDirectStartWithoutAuthorizationAndPreservesOwnedArtifacts() {
        val root = Files.createTempDirectory("batch-corrupt-manifest")
        val store = BatchAudioStore(root)
        val fake = RecordingBatchEngine()
        val original = BatchGenerationRequest(
            batchId = "corrupt-original",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("owned original text"),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        assertEquals(
            BatchGenerationStatus.COMPLETED,
            BatchGenerationSession(fake, store).run(original).status
        )
        val manifest = root.resolve("manifest.json")
        val sidecar = root.resolve("chunk-000000.txt")
        val wav = root.resolve("chunk-000000.wav")
        val malformedManifest = "{ this is not a batch manifest }".toByteArray()
        Files.write(manifest, malformedManifest)
        val sidecarBytes = Files.readAllBytes(sidecar)
        val wavBytes = Files.readAllBytes(wav)
        fake.loadCalls = 0
        fake.generatedTexts.clear()

        val replacement = original.copy(
            batchId = "corrupt-replacement",
            texts = listOf("replacement text")
        )
        val rejected = BatchGenerationSession(fake, store).run(replacement)

        assertEquals(BatchGenerationStatus.FAILED, rejected.status)
        assertTrue(rejected.error.orEmpty().contains("manifest", ignoreCase = true))
        assertEquals(0, fake.loadCalls)
        assertTrue(fake.generatedTexts.isEmpty())
        assertEquals(malformedManifest.toList(), Files.readAllBytes(manifest).toList())
        assertEquals(sidecarBytes.toList(), Files.readAllBytes(sidecar).toList())
        assertEquals(wavBytes.toList(), Files.readAllBytes(wav).toList())

        val authorized = BatchGenerationSession(fake, store).run(
            replacement.copy(allowIncompatibleReplacement = true)
        )

        assertEquals(BatchGenerationStatus.COMPLETED, authorized.status)
        assertEquals(listOf("replacement text"), authorized.manifest.chunks.map { it.text })
        assertEquals(1, fake.loadCalls)
        assertEquals(listOf("replacement text"), fake.generatedTexts)
        assertTrue(Files.readAllBytes(manifest).contentEquals(malformedManifest).not())
        assertEquals("replacement text", Files.readString(sidecar))
    }

    @Test
    fun selectiveCancellationLeavesNonSelectedAndCompleteChunksUntouched() {
        val root = Files.createTempDirectory("batch-selective-cancel")
        val fake = RecordingBatchEngine()
        val texts = listOf("zero", "one", "two")
        val base = BatchGenerationRequest(
            batchId = "selective-cancel",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = texts,
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root,
            onlyIndices = setOf(0)
        )
        BatchGenerationSession(fake, BatchAudioStore(root)).run(base)

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(
            base.copy(onlyIndices = setOf(1)),
            shouldCancel = { true }
        )

        assertEquals(BatchGenerationStatus.CANCELLED, result.status)
        assertEquals(BatchChunkStatus.COMPLETE, result.manifest.chunks.first { it.index == 0 }.status)
        assertEquals(BatchChunkStatus.CANCELLED, result.manifest.chunks.first { it.index == 1 }.status)
        assertEquals(BatchChunkStatus.PENDING, result.manifest.chunks.first { it.index == 2 }.status)
        assertEquals(listOf("zero"), fake.generatedTexts)

        val completeRegenerationCancelled = BatchGenerationSession(fake, BatchAudioStore(root)).run(
            base.copy(regenerateIndices = setOf(0)),
            shouldCancel = { true }
        )
        assertEquals(BatchGenerationStatus.CANCELLED, completeRegenerationCancelled.status)
        assertEquals(BatchChunkStatus.COMPLETE, completeRegenerationCancelled.manifest.chunks.first { it.index == 0 }.status)
        assertEquals(BatchChunkStatus.CANCELLED, completeRegenerationCancelled.manifest.chunks.first { it.index == 1 }.status)
    }

    @Test
    fun overlapsOnePersistenceWriteWithNextGenerationAndCommitsWritesInOrder() {
        val root = Files.createTempDirectory("batch-pipeline")
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val writeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        val secondGenerationStarted = CountDownLatch(1)
        val overlapObserved = java.util.concurrent.atomic.AtomicBoolean(false)
        val writeIndexes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val fake = RecordingBatchEngine { text ->
            if (text == "second") {
                if (writeStarted.await(1, TimeUnit.SECONDS)) {
                    overlapObserved.set(writeInFlight.get())
                    secondGenerationStarted.countDown()
                    releaseWrite.countDown()
                }
            }
        }
        val request = BatchGenerationRequest(
            batchId = "pipeline",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("first", "second"),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(
            request,
            onPersistenceStarted = { index ->
                writeIndexes += index
                if (index == 0) {
                    writeInFlight.set(true)
                    writeStarted.countDown()
                    releaseWrite.await(2, TimeUnit.SECONDS)
                    writeInFlight.set(false)
                }
            },
            onPersistenceCompleted = { index -> writeIndexes += index }
        )

        assertEquals(BatchGenerationStatus.COMPLETED, result.status)
        assertTrue(secondGenerationStarted.await(0, TimeUnit.SECONDS))
        assertTrue(overlapObserved.get())
        assertEquals(listOf(0, 0, 1, 1), writeIndexes)
    }

    @Test
    fun generatedManifestUsesCanonicalIdentityAndContinuesWithoutRewrite() {
        val root = Files.createTempDirectory("batch-canonical-identity")
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "canonical",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("first", "second"),
            languageId = 2050,
            instruction = "Read calmly",
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        val first = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)
        val before = Files.readString(root.resolve("manifest.json"))
        fake.generatedTexts.clear()
        val resumed = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)
        val after = Files.readString(root.resolve("manifest.json"))

        assertEquals(BatchGenerationStatus.COMPLETED, first.status)
        assertEquals(BatchGenerationStatus.COMPLETED, resumed.status)
        assertTrue(fake.generatedTexts.isEmpty())
        assertEquals(before, after)
        assertEquals("PCM_SIGNED_LE_16", resumed.manifest.metadata["pcmEncoding"])
        assertEquals("1", resumed.manifest.metadata["channels"])
        assertTrue(resumed.manifest.metadata["textFingerprint"].orEmpty().isNotBlank())
    }

    @Test
    fun capturedArtifactIdentityAndChecksumSurviveContinuation() {
        val root = Files.createTempDirectory("batch-captured-identity")
        val artifact = root.resolve("captured.json").toFile().apply { writeText("embedding") }
        val reference = root.resolve("reference.wav").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "captured-identity",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("captured text"),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = artifact.absolutePath,
            iclPromptPath = null,
            outputDirectory = root,
            voiceProvenance = "Warm narrator",
            voiceMode = BatchVoiceMode.CAPTURED_VOICE,
            speakerEmbeddingSha256 = BatchIdentity.sha256File(artifact),
            referenceWavPath = reference.absolutePath,
            referenceWavSha256 = BatchIdentity.sha256File(reference)
        )

        val first = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)
        val before = BatchAudioStore(root).loadManifest()
        fake.generatedTexts.clear()
        val resumed = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)

        assertEquals(BatchGenerationStatus.COMPLETED, first.status)
        assertEquals(BatchGenerationStatus.COMPLETED, resumed.status)
        assertTrue(fake.generatedTexts.isEmpty())
        assertEquals("captured-voice", before.metadata["voiceMode"])
        assertEquals(BatchIdentity.sha256File(artifact), before.metadata["speakerEmbeddingSha256"])
        assertEquals(BatchIdentity.sha256File(reference), before.metadata["referenceWavSha256"])
        assertEquals(before, resumed.manifest)
    }

    @Test
    fun nativeLoadFailurePreservesCompatibleManifestWithoutReplacement() {
        val root = Files.createTempDirectory("batch-load-failure")
        val fake = RecordingBatchEngine()
        val request = BatchGenerationRequest(
            batchId = "load-failure",
            modelDir = root.toString(),
            modelName = "model.gguf",
            backendPreference = NativeBackendPreference.Cpu,
            texts = listOf("already complete"),
            languageId = 2050,
            instruction = null,
            speaker = null,
            speakerEmbeddingPath = null,
            iclPromptPath = null,
            outputDirectory = root
        )

        BatchGenerationSession(fake, BatchAudioStore(root)).run(request)
        val beforeBytes = Files.readAllBytes(root.resolve("manifest.json"))
        val before = BatchAudioStore(root).loadManifest()
        fake.loadResult = QwenEngine.NativeOperationResult(false, "native load failed")

        val result = BatchGenerationSession(fake, BatchAudioStore(root)).run(request)

        assertEquals(BatchGenerationStatus.FAILED, result.status)
        assertEquals("native load failed", result.error)
        assertEquals(before, result.manifest)
        assertEquals(beforeBytes.toList(), Files.readAllBytes(root.resolve("manifest.json")).toList())
        assertTrue(fake.generatedTexts.size == 1)
    }

    private class RecordingBatchEngine(
        private val onGenerate: (String) -> Unit = {}
    ) : BatchEngine {
        var loadCalls = 0
        var memory: QwenEngine.BackendMemory? = null
        val generatedTexts = mutableListOf<String>()
        val embeddingPaths = mutableListOf<String?>()
        val instructions = mutableListOf<String?>()
        var loadResult = QwenEngine.NativeOperationResult(success = true)

        override fun loadDetailed(
            modelDir: String,
            modelName: String?,
            backendPreference: NativeBackendPreference
        ) = loadResult
            .also { loadCalls++ }

        override fun supportsReusableBufferedSession() = true

        override fun backendMemory() = memory

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
            onGenerate(text)
            return QwenEngine.NativeResult(
                audio = floatArrayOf(0f, 0.1f),
                sampleRate = 24_000,
                success = true,
                errorMsg = null,
                timeMs = 1L
            )
        }
    }

    private fun memoryThatCapsAt(maxCharacters: Int): QwenEngine.BackendMemory {
        val requiredBudget = BatchMemoryPolicy.estimatedPeakBytes(maxCharacters)
        val freeBytes = (requiredBudget * 100L + 59L) / 60L
        return QwenEngine.BackendMemory(freeBytes, freeBytes)
    }
}
