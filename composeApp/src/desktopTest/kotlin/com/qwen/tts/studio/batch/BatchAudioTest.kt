package com.qwen.tts.studio.batch

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BatchAudioTest {
    @Test
    fun recombinesByNumericIndexAndLeavesNoTempFiles() {
        val root = Files.createTempDirectory("batch-audio")
        val store = BatchAudioStore(root)
        var manifest = store.createManifest("test", 2)
        manifest = store.writeChunk(manifest, 1, GeneratedAudio(floatArrayOf(0.5f), 24_000))
        manifest = store.writeChunk(manifest, 0, GeneratedAudio(floatArrayOf(-0.5f), 24_000))

        val output = store.recombine(manifest, root.resolve("combined.wav"))
        val bytes = Files.readAllBytes(output)
        assertEquals(44 + 4, bytes.size)
        assertContentEquals(byteArrayOf(1, (-64).toByte(), 0, 64), bytes.copyOfRange(44, 48))
        assertEquals(0, Files.list(root).use { stream -> stream.toList().count { path -> path.fileName.toString().contains(".tmp-") } })
    }

    @Test
    fun failsClosedForGapAndFailedChunk() {
        val root = Files.createTempDirectory("batch-audio")
        val store = BatchAudioStore(root)
        var manifest = store.createManifest("test", 2)
        manifest = store.writeChunk(manifest, 0, GeneratedAudio(floatArrayOf(0f), 24_000))
        assertFailsWith<IllegalArgumentException> { store.recombine(manifest, root.resolve("combined.wav")) }
        manifest = store.markFailed(manifest, 1, "generator failed")
        assertFailsWith<IllegalArgumentException> { store.recombine(manifest, root.resolve("combined.wav")) }
    }

    @Test
    fun failsClosedWhenWavFormatOrMetadataDoesNotMatch() {
        val root = Files.createTempDirectory("batch-audio")
        val store = BatchAudioStore(root)
        val manifest = store.writeChunk(store.createManifest("test", 1), 0, GeneratedAudio(floatArrayOf(0.25f), 24_000))
        val incompatible = manifest.copy(chunks = listOf(manifest.chunks.single().copy(sampleRate = 48_000)))
        assertFailsWith<IllegalArgumentException> { store.recombine(incompatible, root.resolve("combined.wav")) }
        Files.write(root.resolve("chunk-000000.wav"), byteArrayOf(0, 1, 2))
        assertFailsWith<IllegalArgumentException> { store.recombine(manifest, root.resolve("combined.wav")) }
    }

    @Test
    fun failsClosedWhenChunksUseDifferentSampleRates() {
        val root = Files.createTempDirectory("batch-audio")
        val store = BatchAudioStore(root)
        var manifest = store.createManifest("test", 2)
        manifest = store.writeChunk(manifest, 0, GeneratedAudio(floatArrayOf(0f), 24_000))
        manifest = store.writeChunk(manifest, 1, GeneratedAudio(floatArrayOf(0f), 48_000))
        assertFailsWith<IllegalArgumentException> { store.recombine(manifest, root.resolve("combined.wav")) }
    }

    @Test
    fun manifestRetainsChunkSourceText() {
        val root = Files.createTempDirectory("batch-audio")
        val store = BatchAudioStore(root)
        val manifest = store.writeChunk(
            store.createManifest("text-provenance", 1),
            0,
            GeneratedAudio(floatArrayOf(0f), 24_000),
            "Hello, \"world\"\nsecond line"
        )

        val json = Files.readString(root.resolve("manifest.json"))
        assertEquals("Hello, \"world\"\nsecond line", manifest.chunks.single().text)
        assertEquals("Hello, \"world\"\nsecond line", Files.readString(root.resolve("chunk-000000.txt")))
        kotlin.test.assertTrue(json.contains("\\\"world\\\""))
        kotlin.test.assertTrue(json.contains("second line"))
    }

    @Test
    fun legacyManifestWithoutDisplayIndexDefaultsToNumericLabel() {
        val root = Files.createTempDirectory("batch-legacy-display-index")
        val store = BatchAudioStore(root)
        Files.writeString(
            root.resolve("legacy.json"),
            """{"batchId":"legacy","expectedChunkCount":1,"metadata":{},"chunks":[{"index":0,"fileName":"chunk-000000.wav","text":"legacy","status":"PENDING"}]}"""
        )

        val loaded = store.loadManifest(root.resolve("legacy.json"))

        assertEquals("0", loaded.chunks.single().displayIndex)
    }

    @Test
    fun alignmentSidecarRoundTripsAndRejectsStaleAudioOrText() {
        val root = Files.createTempDirectory("batch-alignment")
        val store = BatchAudioStore(root)
        val text = "First sentence. Second sentence."
        val manifest = store.writeChunk(store.createManifest("alignment", 1), 0, GeneratedAudio(FloatArray(24_000), 24_000), text)
        val alignment = BatchChunkAlignment(
            textSha256 = sha256Text(text),
            wavSha256 = manifest.chunks.single().sha256!!,
            sampleRate = 24_000,
            durationSeconds = 1f,
            quality = "APPROXIMATE",
            method = "test",
            spans = listOf(
                BatchAlignmentSpan(0, 15, 0f, 0.45f, 0.55f),
                BatchAlignmentSpan(16, text.length, 0.45f, 1f, 0.55f)
            )
        )
        store.writeAlignment(0, alignment)

        assertEquals(alignment, readValidAlignment(root, manifest.chunks.single(), 1f))
        assertEquals(null, readValidAlignment(root, manifest.chunks.single().copy(text = "changed"), 1f))
        assertEquals(null, readValidAlignment(root, manifest.chunks.single().copy(sha256 = "stale"), 1f))
        assertEquals(null, readValidAlignment(root, manifest.chunks.single(), 1.4f))
    }

    @Test
    fun validationOutcomeRoundTripsInManifest() {
        val root = Files.createTempDirectory("batch-validation-state")
        val store = BatchAudioStore(root)
        val manifest = store.writeChunk(
            store.createManifest("validation-state", 1),
            0,
            GeneratedAudio(FloatArray(24_000), 24_000),
            "validated text"
        ).copy(chunks = store.loadManifest().chunks.map {
            it.copy(
                validationPassed = false,
                validationMessage = "ASR suffix mismatch",
                validationSignature = BatchValidationSignature.forChunk(it)
            )
        })
        store.persistManifest(manifest)

        val reloaded = store.loadManifest()
        assertEquals(false, reloaded.chunks.single().validationPassed)
        assertEquals("ASR suffix mismatch", reloaded.chunks.single().validationMessage)
        assertEquals(BatchValidationSignature.forChunk(reloaded.chunks.single()), reloaded.chunks.single().validationSignature)
    }

    @Test
    fun validationSignatureChangesWhenChunkInputsChange() {
        val chunk = BatchChunk(
            index = 0,
            fileName = "chunk-000000.wav",
            text = "Transcript",
            voiceName = "vivian",
            modelName = "model.gguf",
            voicePrompt = "Calm"
        )
        val signature = BatchValidationSignature.forChunk(chunk)

        assertEquals(true, BatchValidationSignature.matches(chunk.copy(validationSignature = signature)))
        assertEquals(false, BatchValidationSignature.matches(chunk.copy(text = "Changed", validationSignature = signature)))
        assertEquals(false, BatchValidationSignature.matches(chunk.copy(modelName = "other.gguf", validationSignature = signature)))
        assertEquals(false, BatchValidationSignature.matches(chunk.copy(voicePrompt = "Bright", validationSignature = signature)))
        assertEquals(false, BatchValidationSignature.matches(chunk.copy(voiceName = "other", validationSignature = signature)))
    }

    @Test
    fun staleManifestRevisionCannotOverwriteAnotherStoreUpdate() {
        val root = Files.createTempDirectory("batch-manifest-cas")
        val first = BatchAudioStore(root)
        val second = BatchAudioStore(root)
        first.createManifest("manifest-cas", 1)
        val stale = first.loadManifest()

        second.updateManifest { current ->
            current.copy(metadata = current.metadata + ("winner" to "second-store"))
        }

        assertFailsWith<BatchManifestConflictException> {
            first.persistManifest(stale.copy(metadata = stale.metadata + ("winner" to "stale-store")))
        }
        assertEquals("second-store", first.loadManifest().metadata["winner"])
    }

    @Test
    fun separateStoreInstancesMergeConcurrentChunkUpdatesWithoutLostRows() {
        val root = Files.createTempDirectory("batch-manifest-lock")
        val first = BatchAudioStore(root)
        val second = BatchAudioStore(root)
        first.createManifest("manifest-lock", 2)
        first.updateManifest { current ->
            current.withChunk(BatchChunk(0, "chunk-000000.wav", "first"))
                .withChunk(BatchChunk(1, "chunk-000001.wav", "second"))
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                executor.submit {
                    start.await(2, TimeUnit.SECONDS)
                    first.updateManifest { current ->
                        current.withChunk(current.chunks.single { it.index == 0 }.copy(
                            status = BatchChunkStatus.FAILED,
                            error = "first-store"
                        ))
                    }
                },
                executor.submit {
                    start.await(2, TimeUnit.SECONDS)
                    second.updateManifest { current ->
                        current.withChunk(current.chunks.single { it.index == 1 }.copy(
                            status = BatchChunkStatus.FAILED,
                            error = "second-store"
                        ))
                    }
                }
            )
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val merged = first.loadManifest()
        assertEquals("first-store", merged.chunks.single { it.index == 0 }.error)
        assertEquals("second-store", merged.chunks.single { it.index == 1 }.error)
    }

    @Test
    fun validationLeaseRejectsReplacementThatReusesAnIntegerIndex() {
        val root = Files.createTempDirectory("batch-validation-lease")
        val store = BatchAudioStore(root)
        val complete = store.writeChunk(
            store.createManifest("validation-lease", 1),
            0,
            GeneratedAudio(floatArrayOf(0f, 0.1f), 24_000),
            "original"
        )
        val lease = store.captureBatchChunkValidationLease(0)
        store.updateManifest { current ->
            current.withChunk(current.chunks.single().copy(
                text = "replacement",
                status = BatchChunkStatus.PENDING,
                sampleRate = null,
                frameCount = null,
                sha256 = null,
                validationPassed = null,
                validationMessage = null,
                validationSignature = null
            ))
        }

        assertFailsWith<BatchManifestConflictException> {
            store.persistChunkValidation(0, passed = true, message = "stale pass", expectedLease = lease)
        }
        val current = store.loadManifest().chunks.single()
        assertEquals("replacement", current.text)
        assertEquals(BatchChunkStatus.PENDING, current.status)
        assertEquals(null, current.validationPassed)
        assertEquals(complete.chunks.single().sha256, Files.readAllBytes(root.resolve("chunk-000000.wav")).let { bytes ->
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        })
    }

    @Test
    fun staleCompletionCannotOverwriteRechunkedLogicalRow() {
        val root = Files.createTempDirectory("batch-completion-lease")
        val store = BatchAudioStore(root)
        val generated = store.writeChunk(
            store.createManifest("completion-lease", 1),
            0,
            GeneratedAudio(floatArrayOf(0f), 24_000),
            "old text"
        ).chunks.single()
        store.updateManifest { current ->
            current.withChunk(current.chunks.single().copy(
                text = "new text",
                status = BatchChunkStatus.PENDING,
                sampleRate = null,
                frameCount = null,
                sha256 = null,
                validationPassed = null,
                validationMessage = null,
                validationSignature = null
            ))
        }

        assertFailsWith<BatchManifestConflictException> {
            store.persistCompletedChunk(generated)
        }
        assertEquals("new text", store.loadManifest().chunks.single().text)
    }

    @Test
    fun manifestRetainsPerChunkVoiceModelAndPromptAcrossWriteAndReload() {
        val root = Files.createTempDirectory("batch-config-reload")
        val store = BatchAudioStore(root)
        val configured = store.createManifest("config", 1).withChunk(
            BatchChunk(
                index = 0,
                fileName = "chunk-000000.wav",
                text = "configured text",
                voiceName = "Ryan",
                modelName = "qwen-talker-1.7b-customvoice-Q8_0.gguf",
                voicePrompt = "Warm, measured delivery"
            )
        )
        val written = store.writeChunk(configured, 0, GeneratedAudio(floatArrayOf(0f), 24_000), "configured text")
        val validated = store.persistChunkValidation(0, true, "Deterministic and ASR validation passed.")
        val loaded = store.loadManifest()

        assertEquals(written.chunks.single().voiceName, loaded.chunks.single().voiceName)
        assertEquals(written.chunks.single().modelName, loaded.chunks.single().modelName)
        assertEquals(written.chunks.single().voicePrompt, loaded.chunks.single().voicePrompt)
        assertEquals(true, validated.chunks.single().validationPassed)

        val resumed = store.createOrResumeManifest("new-ui-run", listOf("configured text"))
        assertEquals("Ryan", resumed.chunks.single().voiceName)
        assertEquals("qwen-talker-1.7b-customvoice-Q8_0.gguf", resumed.chunks.single().modelName)
        assertEquals("Warm, measured delivery", resumed.chunks.single().voicePrompt)
        assertEquals(true, resumed.chunks.single().validationPassed)
        assertEquals("Deterministic and ASR validation passed.", resumed.chunks.single().validationMessage)
        assertEquals(validated.chunks.single().validationSignature, resumed.chunks.single().validationSignature)
    }

    @Test
    fun resumesOnlyValidChunksFromCompatibleManifest() {
        val root = Files.createTempDirectory("batch-resume")
        val store = BatchAudioStore(root)
        val metadata = mapOf("textFingerprint" to "fingerprint", "modelDir" to "model")
        var manifest = store.createManifest("resume", 2, metadata)
        manifest = store.writeChunk(manifest, 0, GeneratedAudio(floatArrayOf(0.1f, 0.2f), 24_000), "first")
        Files.write(root.resolve("chunk-000001.wav"), byteArrayOf(1, 2, 3))

        val resumed = store.createOrResumeManifest("resume", listOf("first", "second"), metadata)

        assertEquals(listOf(0, 1), resumed.chunks.map { it.index })
        assertEquals(BatchChunkStatus.COMPLETE, resumed.chunks.first { it.index == 0 }.status)
        assertEquals(BatchChunkStatus.PENDING, resumed.chunks.first { it.index == 1 }.status)
        assertEquals("first", resumed.chunks.first { it.index == 0 }.text)
        assertEquals("second", resumed.chunks.first { it.index == 1 }.text)
    }

    @Test
    fun rejectsTamperedButValidWavDuringResume() {
        val root = Files.createTempDirectory("batch-tampered-wav")
        val store = BatchAudioStore(root)
        val metadata = mapOf("textFingerprint" to "fingerprint", "modelDir" to "model")
        store.writeChunk(
            store.createManifest("tampered", 1, metadata),
            0,
            GeneratedAudio(floatArrayOf(0.1f, 0.2f), 24_000),
            "unchanged source"
        )
        val wav = Files.readAllBytes(root.resolve("chunk-000000.wav"))
        wav[44] = (wav[44].toInt() xor 0x01).toByte()
        Files.write(root.resolve("chunk-000000.wav"), wav)

        val resumed = store.createOrResumeManifest("tampered", listOf("unchanged source"), metadata)
        val chunk = resumed.chunks.single()
        val persisted = store.loadManifest(root.resolve("manifest.json"))

        assertEquals(BatchChunkStatus.PENDING, chunk.status)
        assertEquals(null, chunk.sha256)
        assertEquals(null, persisted.chunks.single().sha256)
        assertEquals(BatchChunkStatus.PENDING, persisted.chunks.single().status)
    }

    @Test
    fun doesNotTreatAnOldWavAsCompleteAfterManifestMarksChunkFailed() {
        val root = Files.createTempDirectory("batch-stale-wav")
        val store = BatchAudioStore(root)
        val metadata = mapOf("textFingerprint" to "fingerprint", "modelDir" to "model")
        var manifest = store.createManifest("stale", 1, metadata)
        manifest = store.writeChunk(manifest, 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "original")
        manifest = store.markFailed(manifest, 0, "replacement failed", "replacement")

        val resumed = store.createOrResumeManifest("stale", listOf("replacement"), metadata)

        assertEquals(BatchChunkStatus.PENDING, resumed.chunks.single().status)
        assertEquals("replacement", resumed.chunks.single().text)
    }

    @Test
    fun loadsManifestAsReplayableArtifact() {
        val root = Files.createTempDirectory("batch-manifest-replay")
        val store = BatchAudioStore(root)
        val metadata = mapOf(
            "modelDir" to "D:/models",
            "modelName" to "talker.gguf",
            "backendPreference" to "cuda",
            "instruction" to "Calm delivery",
            "languageId" to "2050"
        )
        var manifest = store.createManifest("replayable", 2, metadata)
        manifest = store.writeChunk(manifest, 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "first\n\nchunk")

        val loaded = store.loadManifest(root.resolve("manifest.json"))

        assertEquals("replayable", loaded.batchId)
        assertEquals(2, loaded.expectedChunkCount)
        assertEquals(metadata, loaded.metadata)
        assertEquals("first\n\nchunk", loaded.chunks.single().text)
        assertEquals(BatchChunkStatus.COMPLETE, loaded.chunks.single().status)
    }

    @Test
    fun generatesManifestFromChunkSidecarsAndExistingWavs() {
        val root = Files.createTempDirectory("batch-manifest-generate")
        val store = BatchAudioStore(root)
        val metadata = mapOf("modelDir" to "model")
        store.writeChunk(store.createManifest("seed", 1, metadata), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "sidecar text")

        val generated = store.generateManifestFromSidecars("rebuilt", metadata)

        assertEquals("rebuilt", generated.batchId)
        assertEquals(BatchChunkStatus.COMPLETE, generated.chunks.single().status)
        assertEquals("sidecar text", generated.chunks.single().text)
        assertEquals(mapOf("modelDir" to "model"), generated.metadata)
    }

    @Test
    fun freshTextScanLeavesParseableButUnprovenWavPending() {
        val root = Files.createTempDirectory("batch-fresh-text-scan")
        val seed = BatchAudioStore(Files.createTempDirectory("batch-seed"))
        val seedManifest = seed.writeChunk(seed.createManifest("seed", 1), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "old text")
        Files.write(root.resolve("chunk-000000.wav"), Files.readAllBytes(seed.directoryPath.resolve(seedManifest.chunks.single().fileName)))

        val store = BatchAudioStore(root)
        val generated = store.generateManifestFromTexts("fresh", listOf("new text"))

        assertEquals(BatchChunkStatus.PENDING, generated.chunks.single().status)
        assertEquals("new text", Files.readString(root.resolve("chunk-000000.txt")))
    }

    @Test
    fun freshTextManifestPersistsBatchVoiceDefaultsOnEveryChunk() {
        val root = Files.createTempDirectory("batch-voice-defaults")
        val generated = BatchAudioStore(root).generateManifestFromTexts(
            batchId = "voice-defaults",
            texts = listOf("first", "second"),
            defaultVoiceName = "vivian",
            defaultModelName = "qwen-talker-custom.gguf",
            defaultVoicePrompt = "calm"
        )

        assertEquals(listOf("vivian", "vivian"), generated.chunks.map { it.voiceName })
        assertEquals(listOf("qwen-talker-custom.gguf", "qwen-talker-custom.gguf"), generated.chunks.map { it.modelName })
        assertEquals(listOf("calm", "calm"), generated.chunks.map { it.voicePrompt })
        val reloaded = BatchAudioStore(root).loadManifest()
        assertEquals(generated.chunks.map { it.voiceName }, reloaded.chunks.map { it.voiceName })
    }

    @Test
    fun freshSidecarScanLeavesParseableButUnprovenWavPending() {
        val root = Files.createTempDirectory("batch-fresh-sidecar-scan")
        val seed = BatchAudioStore(Files.createTempDirectory("batch-seed"))
        val seedManifest = seed.writeChunk(seed.createManifest("seed", 1), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "sidecar text")
        Files.write(root.resolve("chunk-000000.wav"), Files.readAllBytes(seed.directoryPath.resolve(seedManifest.chunks.single().fileName)))
        Files.writeString(root.resolve("chunk-000000.txt"), "sidecar text")

        val generated = BatchAudioStore(root).generateManifestFromSidecars("fresh")

        assertEquals(BatchChunkStatus.PENDING, generated.chunks.single().status)
    }

    @Test
    fun matchingProvenanceIsAcceptedByBothScanPaths() {
        val textRoot = Files.createTempDirectory("batch-matching-text")
        val metadata = mapOf("modelDir" to "model")
        val textStore = BatchAudioStore(textRoot)
        textStore.writeChunk(textStore.createManifest("seed", 1, metadata), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "same text")

        val fromTexts = textStore.generateManifestFromTexts("replay", listOf("same text"), metadata)
        val fromSidecars = textStore.generateManifestFromSidecars("replay-sidecars", metadata)

        assertEquals(BatchChunkStatus.COMPLETE, fromTexts.chunks.single().status)
        assertEquals(BatchChunkStatus.COMPLETE, fromSidecars.chunks.single().status)
    }

    @Test
    fun staleWavRemainsPendingForBothScanPaths() {
        val textRoot = Files.createTempDirectory("batch-stale-text")
        val metadata = mapOf("modelDir" to "model")
        val textStore = BatchAudioStore(textRoot)
        textStore.writeChunk(textStore.createManifest("seed", 1, metadata), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "old text")

        val fromTexts = textStore.generateManifestFromTexts("new-text", listOf("new text"), metadata)
        assertEquals(BatchChunkStatus.PENDING, fromTexts.chunks.single().status)

        val sidecarRoot = Files.createTempDirectory("batch-stale-sidecar")
        val sidecarStore = BatchAudioStore(sidecarRoot)
        sidecarStore.writeChunk(sidecarStore.createManifest("seed", 1, metadata), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "old text")
        Files.writeString(sidecarRoot.resolve("chunk-000000.txt"), "new text")

        val fromSidecars = sidecarStore.generateManifestFromSidecars("new-sidecar", metadata)
        assertEquals(BatchChunkStatus.PENDING, fromSidecars.chunks.single().status)
    }

    @Test
    fun incompatibleTextInputDoesNotOverwriteExistingSidecarOrManifest() {
        val root = Files.createTempDirectory("batch-incompatible-provenance")
        val store = BatchAudioStore(root)
        val oldMetadata = mapOf("modelDir" to "old-model")
        store.writeChunk(store.createManifest("seed", 1, oldMetadata), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "old text")
        val oldManifest = Files.readAllBytes(root.resolve("manifest.json"))
        val oldSidecar = Files.readAllBytes(root.resolve("chunk-000000.txt"))

        assertFailsWith<IllegalArgumentException> {
            store.generateManifestFromTexts("new", listOf("new text"), mapOf("modelDir" to "new-model"))
        }

        assertContentEquals(oldManifest, Files.readAllBytes(root.resolve("manifest.json")))
        assertContentEquals(oldSidecar, Files.readAllBytes(root.resolve("chunk-000000.txt")))
    }

    @Test
    fun deterministicValidationPassesForMatchingSourceAndAudio() {
        val root = Files.createTempDirectory("batch-validation")
        val store = BatchAudioStore(root)
        val text = "hello world"
        val manifest = store.writeChunk(store.createManifest("validation", 1), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), text)

        val report = BatchValidator.validate(store, manifest, text)

        kotlin.test.assertTrue(report.passed)
        assertEquals(0, report.errors.size)
    }

    @Test
    fun refusesToOverwriteChunkDuringRecombine() {
        val root = Files.createTempDirectory("batch-audio")
        val store = BatchAudioStore(root)
        val manifest = store.writeChunk(
            store.createManifest("safe-output", 1),
            0,
            GeneratedAudio(floatArrayOf(0f), 24_000),
            "one"
        )

        assertFailsWith<IllegalArgumentException> {
            store.recombine(manifest, root.resolve("chunk-000000.wav"))
        }
    }

    @Test
    fun rechunksFailedSourceInPlaceWithDecimalLogicalIndexesAndPreservesCompleteAudio() {
        val root = Files.createTempDirectory("batch-rechunk")
        val store = BatchAudioStore(root)
        val failedText = "Failed chunk sentence one. Failed chunk sentence two. Failed chunk sentence three."
        val metadata = mapOf(
            "textFingerprint" to "old-fingerprint",
            "audioCapTokens.0" to "101",
            "audioCapTokens.1" to "102",
            "audioCapTokens.2" to "103"
        )
        var manifest = store.createManifest("rechunk", 3, metadata)
        manifest = store.writeChunk(manifest, 0, GeneratedAudio(floatArrayOf(-0.1f), 24_000), "keep zero")
        manifest = store.writeChunk(manifest, 1, GeneratedAudio(floatArrayOf(0.1f), 24_000), failedText)
        manifest = store.writeChunk(manifest, 2, GeneratedAudio(floatArrayOf(0.2f), 24_000), "keep two")
        val zeroSha = manifest.chunks.first { it.index == 0 }.sha256
        manifest = store.markFailed(manifest, 1, "ASR suffix mismatch")

        val result = store.rechunkFailedChunks(manifest, maxCharacters = 20)
        val updated = result.manifest
        val reloaded = store.loadManifest()

        assertTrue(Files.isRegularFile(result.backupManifest))
        assertEquals(3, store.loadManifest(result.backupManifest).expectedChunkCount)
        assertTrue(updated.expectedChunkCount > 3)
        assertEquals(updated, reloaded)
        assertEquals("0", updated.chunks.first().displayIndex)
        assertEquals("2", updated.chunks.last().displayIndex)
        assertTrue(updated.chunks.drop(1).dropLast(1).all { it.displayIndex.startsWith("1.") })
        assertTrue(updated.chunks.drop(1).dropLast(1).all { it.status == BatchChunkStatus.FAILED || it.status == BatchChunkStatus.PENDING })
        assertEquals(zeroSha, updated.chunks.first { it.displayIndex == "0" }.sha256)
        assertEquals(BatchChunkStatus.COMPLETE, updated.chunks.first { it.displayIndex == "0" }.status)
        assertEquals(BatchChunkStatus.COMPLETE, updated.chunks.first { it.displayIndex == "2" }.status)
        assertEquals(null, updated.metadata["audioCapTokens.1"])
        assertEquals("101", updated.metadata["audioCapTokens.0"])
        val tailIndex = updated.chunks.first { it.displayIndex == "2" }.index
        assertEquals("103", updated.metadata["audioCapTokens.$tailIndex"])
        assertEquals(
            manifest.chunks.sortedBy { it.index }.joinToString("") { it.text },
            updated.chunks.sortedBy { it.index }.joinToString("") { it.text }
        )
        assertTrue(Files.list(root).use { stream -> stream.toList().none { it.fileName.toString().startsWith(".rechunk-staging-") } })
        assertTrue(Files.readString(root.resolve("manifest.json")).contains("\"displayIndex\":\"1.1\""))
    }

    @Test
    fun retainsShortFailedSourceAsSingleRetryChild() {
        val root = Files.createTempDirectory("batch-rechunk-short")
        val store = BatchAudioStore(root)
        var manifest = store.createManifest(
            "rechunk-short",
            1,
            mapOf("audioCapTokens.0" to "101")
        )
        val shortText = "Short failed text."
        manifest = store.markFailed(manifest, 0, "ASR mismatch", shortText)

        val result = store.rechunkFailedChunks(manifest, maxCharacters = 40)
        val retry = result.manifest.chunks.single()

        assertTrue(Files.isRegularFile(result.backupManifest))
        assertEquals(1, result.manifest.expectedChunkCount)
        assertEquals("0.1", retry.displayIndex)
        assertEquals(shortText, retry.text)
        assertEquals(BatchChunkStatus.PENDING, retry.status)
        assertEquals(null, result.manifest.metadata["audioCapTokens.0"])
    }

    @Test
    fun mergesShortFailedTailWithAdjacentSpeechBeforeRetry() {
        val root = Files.createTempDirectory("batch-rechunk-merge-short")
        val store = BatchAudioStore(root)
        val precedingText = "前".repeat(30)
        val tailText = "截。"
        var manifest = store.createManifest("rechunk-merge-short", 2)
        manifest = store.writeChunk(
            manifest,
            0,
            GeneratedAudio(floatArrayOf(0.1f), 24_000),
            precedingText
        )
        manifest = store.markFailed(manifest, 1, "ASR mismatch", tailText)

        val result = store.rechunkFailedChunks(manifest, maxCharacters = 40)
        val retry = result.manifest.chunks.single()

        assertEquals(1, result.manifest.expectedChunkCount)
        assertEquals("0.1", retry.displayIndex)
        assertEquals(precedingText + tailText, retry.text)
        assertEquals(BatchChunkStatus.PENDING, retry.status)
        assertEquals(listOf(0), result.sourceToNewIndexes[0])
        assertEquals(listOf(0), result.sourceToNewIndexes[1])
    }
}
