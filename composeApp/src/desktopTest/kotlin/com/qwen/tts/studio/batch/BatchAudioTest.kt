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
        store.writeChunk(store.createManifest("seed", 1), 0, GeneratedAudio(floatArrayOf(0.1f), 24_000), "sidecar text")

        val generated = store.generateManifestFromSidecars("rebuilt", mapOf("modelDir" to "model"))

        assertEquals("rebuilt", generated.batchId)
        assertEquals(BatchChunkStatus.COMPLETE, generated.chunks.single().status)
        assertEquals("sidecar text", generated.chunks.single().text)
        assertEquals(mapOf("modelDir" to "model"), generated.metadata)
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
