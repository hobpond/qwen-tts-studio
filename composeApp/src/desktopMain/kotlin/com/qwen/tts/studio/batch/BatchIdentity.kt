package com.qwen.tts.studio.batch

import com.qwen.tts.studio.engine.NativeBackendPreference
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

enum class BatchVoiceMode(val id: String) {
    CAPTURED_VOICE("captured-voice"),
    NAMED_SPEAKER("named-speaker"),
    ICL_PROMPT("icl-prompt"),
    SPEAKER_EMBEDDING("speaker-embedding"),
    MODEL_DEFAULT("model-default");

    companion object {
        fun fromId(value: String?): BatchVoiceMode? = entries.firstOrNull { it.id == value }
    }
}

/** The complete stable identity used to decide whether a batch can be replayed. */
data class BatchIdentityData(
    val modelDir: String,
    val modelName: String?,
    val backendPreference: NativeBackendPreference,
    val voiceMode: BatchVoiceMode,
    val speaker: String?,
    val instruction: String?,
    val voiceProvenance: String?,
    val speakerEmbeddingPath: String?,
    val speakerEmbeddingSha256: String?,
    val referenceWavPath: String?,
    val referenceWavSha256: String?,
    val iclPromptPath: String?,
    val iclPromptSha256: String?,
    val languageId: Int,
    val textFingerprint: String
) {
    fun metadata(): Map<String, String> = linkedMapOf(
        "modelDir" to modelDir,
        "modelName" to (modelName ?: ""),
        "backendPreference" to backendPreference.id,
        "voiceMode" to voiceMode.id,
        "speaker" to (speaker ?: ""),
        "instruction" to (instruction ?: ""),
        "voiceProvenance" to (voiceProvenance ?: ""),
        "speakerEmbeddingPath" to (speakerEmbeddingPath ?: ""),
        "speakerEmbeddingSha256" to (speakerEmbeddingSha256 ?: ""),
        "referenceWavPath" to (referenceWavPath ?: ""),
        "referenceWavSha256" to (referenceWavSha256 ?: ""),
        "iclPromptPath" to (iclPromptPath ?: ""),
        "iclPromptSha256" to (iclPromptSha256 ?: ""),
        "languageId" to languageId.toString(),
        "channels" to "1",
        "pcmEncoding" to "PCM_SIGNED_LE_16",
        "textFingerprint" to textFingerprint
    )
}

/** One source of truth for batch identity, replay reconstruction, and compatibility. */
object BatchIdentity {
    private const val APP_DIRECTORY = ".qwen-tts-studio"
    private const val VOICE_ARTIFACT_DIRECTORY = "batch-voice-artifacts"

    fun forRequest(request: BatchGenerationRequest): BatchIdentityData = fromValues(
        modelDir = request.modelDir,
        modelName = request.modelName,
        backendPreference = request.backendPreference,
        voiceMode = request.voiceMode,
        speaker = request.speaker,
        instruction = request.instruction,
        voiceProvenance = request.voiceProvenance,
        speakerEmbeddingPath = request.speakerEmbeddingPath,
        speakerEmbeddingSha256 = request.speakerEmbeddingSha256,
        referenceWavPath = request.referenceWavPath,
        referenceWavSha256 = request.referenceWavSha256,
        iclPromptPath = request.iclPromptPath,
        iclPromptSha256 = request.iclPromptSha256,
        languageId = request.languageId,
        texts = request.texts
    )

    fun fromManifestMetadata(metadata: Map<String, String>, texts: List<String>): BatchIdentityData {
        require(texts.isNotEmpty()) { "Manifest identity requires source text" }
        val data = fromValues(
            modelDir = metadata["modelDir"] ?: error("Manifest is missing modelDir metadata."),
            modelName = metadata["modelName"],
            backendPreference = NativeBackendPreference.fromId(metadata["backendPreference"]),
            voiceMode = BatchVoiceMode.fromId(metadata["voiceMode"]),
            speaker = metadata["speaker"],
            instruction = metadata["instruction"],
            voiceProvenance = metadata["voiceProvenance"],
            speakerEmbeddingPath = metadata["speakerEmbeddingPath"],
            speakerEmbeddingSha256 = metadata["speakerEmbeddingSha256"],
            referenceWavPath = metadata["referenceWavPath"],
            referenceWavSha256 = metadata["referenceWavSha256"],
            iclPromptPath = metadata["iclPromptPath"],
            iclPromptSha256 = metadata["iclPromptSha256"],
            languageId = metadata["languageId"]?.toIntOrNull() ?: 0,
            texts = texts
        )
        metadata["textFingerprint"]?.takeIf(String::isNotBlank)?.let { stored ->
            require(stored == data.textFingerprint) {
                "Manifest text fingerprint does not match its ordered source chunks."
            }
        }
        return data
    }

    fun isCompatible(manifest: BatchManifest?, identity: BatchIdentityData, expectedChunkCount: Int): Boolean =
        manifest != null &&
            manifest.expectedChunkCount == expectedChunkCount &&
            identity.metadata().all { (key, value) -> manifest.metadata[key] == value }

    /** Returns the durable app-managed location for captured voice artifacts. */
    fun durableVoiceArtifactDirectory(appDirectory: File? = null): File =
        File(appDirectory ?: File(System.getProperty("user.home"), APP_DIRECTORY), VOICE_ARTIFACT_DIRECTORY)

    fun sha256File(path: String): String = sha256File(File(path))

    fun sha256File(file: File): String {
        require(file.isFile) { "Artifact does not exist: ${file.absolutePath}" }
        return sha256(Files.readAllBytes(file.toPath()))
    }

    fun sha256FileOrNull(path: String?): String? = path
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { sha256File(it) }.getOrNull() }

    fun requireArtifactIntegrity(identity: BatchIdentityData) {
        listOf(
            identity.speakerEmbeddingPath to identity.speakerEmbeddingSha256,
            identity.referenceWavPath to identity.referenceWavSha256,
            identity.iclPromptPath to identity.iclPromptSha256
        ).forEach { (path, expected) ->
            if (!path.isNullOrBlank() && !expected.isNullOrBlank()) {
                require(sha256File(path) == expected) {
                    "Batch conditioning artifact checksum mismatch: $path"
                }
            }
        }
    }

    /** Verifies the request's persisted conditioning hashes immediately before native work. */
    fun requireArtifactIntegrity(request: BatchGenerationRequest) {
        listOf(
            request.speakerEmbeddingPath to request.speakerEmbeddingSha256,
            request.referenceWavPath to request.referenceWavSha256,
            request.iclPromptPath to request.iclPromptSha256
        ).forEach { (path, expected) ->
            if (!path.isNullOrBlank()) {
                require(!expected.isNullOrBlank()) {
                    "Batch conditioning artifact has no persisted checksum: $path"
                }
                require(sha256File(path) == expected) {
                    "Batch conditioning artifact checksum mismatch: $path"
                }
            }
        }
    }

    private fun fromValues(
        modelDir: String,
        modelName: String?,
        backendPreference: NativeBackendPreference,
        voiceMode: BatchVoiceMode?,
        speaker: String?,
        instruction: String?,
        voiceProvenance: String?,
        speakerEmbeddingPath: String?,
        speakerEmbeddingSha256: String?,
        referenceWavPath: String?,
        referenceWavSha256: String?,
        iclPromptPath: String?,
        iclPromptSha256: String?,
        languageId: Int,
        texts: List<String>
    ): BatchIdentityData {
        val canonicalEmbeddingPath = canonicalPath(speakerEmbeddingPath)
        val canonicalReferencePath = canonicalPath(referenceWavPath)
        val canonicalIclPath = canonicalPath(iclPromptPath)
        val canonicalMode = voiceMode ?: when {
            !voiceProvenance.isNullOrBlank() && !canonicalEmbeddingPath.isNullOrBlank() -> BatchVoiceMode.CAPTURED_VOICE
            !speaker.isNullOrBlank() -> BatchVoiceMode.NAMED_SPEAKER
            !canonicalIclPath.isNullOrBlank() -> BatchVoiceMode.ICL_PROMPT
            !canonicalEmbeddingPath.isNullOrBlank() -> BatchVoiceMode.SPEAKER_EMBEDDING
            else -> BatchVoiceMode.MODEL_DEFAULT
        }
        return BatchIdentityData(
            modelDir = File(modelDir).absoluteFile.normalize().path,
            modelName = modelName?.trim().takeUnless { it.isNullOrEmpty() },
            backendPreference = backendPreference,
            voiceMode = canonicalMode,
            speaker = speaker?.trim().takeUnless { it.isNullOrEmpty() },
            instruction = instruction?.trim().takeUnless { it.isNullOrEmpty() },
            voiceProvenance = voiceProvenance?.trim().takeUnless { it.isNullOrEmpty() },
            speakerEmbeddingPath = canonicalEmbeddingPath,
            speakerEmbeddingSha256 = speakerEmbeddingSha256?.trim().takeUnless { it.isNullOrEmpty() }
                ?: sha256FileOrNull(canonicalEmbeddingPath),
            referenceWavPath = canonicalReferencePath,
            referenceWavSha256 = referenceWavSha256?.trim().takeUnless { it.isNullOrEmpty() }
                ?: sha256FileOrNull(canonicalReferencePath),
            iclPromptPath = canonicalIclPath,
            iclPromptSha256 = iclPromptSha256?.trim().takeUnless { it.isNullOrEmpty() }
                ?: sha256FileOrNull(canonicalIclPath),
            languageId = languageId,
            textFingerprint = textFingerprint(texts)
        )
    }

    private fun canonicalPath(path: String?): String? = path
        ?.takeIf(String::isNotBlank)
        ?.let { File(it).absoluteFile.normalize().path }

    private fun textFingerprint(texts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        texts.forEach { text ->
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

}
