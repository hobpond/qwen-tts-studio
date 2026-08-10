package com.qwen.tts.studio.batch

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
