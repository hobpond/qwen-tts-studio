package com.qwen.tts.studio.batch

import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.io.FileInputStream
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

    fun isCompatible(
        manifest: BatchManifest?,
        identity: BatchIdentityData,
        expectedChunkCount: Int,
        additionalMetadata: Map<String, String> = emptyMap()
    ): Boolean =
        manifest != null &&
            manifest.expectedChunkCount == expectedChunkCount &&
            (identity.metadata() + additionalMetadata).all { (key, value) -> manifest.metadata[key] == value }

    /** Fingerprints the exact model file selected by a native batch request. */
    fun modelArtifactMetadata(modelDir: String, modelName: String?): Map<String, String> =
        fileArtifactMetadata("modelFile", resolveModelFile(modelDir, modelName))

    /** Fingerprints an ASR or other auxiliary model after it is selected. */
    fun auxiliaryArtifactMetadata(prefix: String, file: File): Map<String, String> =
        fileArtifactMetadata(prefix, file)

    /** Adds the loaded native DLL, dependencies, backend, and model capability identity. */
    fun nativeRuntimeMetadata(
        runtime: QwenEngine.NativeRuntimeIdentity,
        capabilities: QwenEngine.NativeCapabilities?
    ): Map<String, String> {
        val metadata = linkedMapOf(
            "manifestIdentityVersion" to "2",
            "nativeRootPath" to runtime.rootPath,
            "nativeLibraryName" to runtime.nativeLibrary.fileName,
            "nativeLibraryPath" to runtime.nativeLibrary.absolutePath,
            "nativeLibrarySizeBytes" to runtime.nativeLibrary.sizeBytes.toString(),
            "nativeLibrarySha256" to runtime.nativeLibrary.sha256,
            "nativeBackendName" to (runtime.activeBackendName ?: ""),
            "nativeCompiledBackendMask" to runtime.compiledBackendMask.toString(),
            "nativeRuntimeOs" to (System.getProperty("os.name") ?: "unknown"),
            "nativeRuntimeArch" to (System.getProperty("os.arch") ?: "unknown"),
            "appVersion" to (System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "1.0.0"),
            "nativeDependencyCount" to runtime.dependencies.size.toString()
        )
        val allArtifacts = listOf(runtime.nativeLibrary) + runtime.dependencies
        metadata["nativeRuntimeFingerprint"] = sha256Text(
            allArtifacts.sortedWith(compareBy({ it.role }, { it.fileName }, { it.sha256 }))
                .joinToString("\n") { "${it.role}|${it.fileName}|${it.sizeBytes}|${it.sha256}" }
        )
        runtime.dependencies.forEachIndexed { index, artifact ->
            val prefix = "nativeDependency.$index."
            metadata[prefix + "role"] = artifact.role
            metadata[prefix + "name"] = artifact.fileName
            metadata[prefix + "path"] = artifact.absolutePath
            metadata[prefix + "sizeBytes"] = artifact.sizeBytes.toString()
            metadata[prefix + "sha256"] = artifact.sha256
        }
        capabilities?.let { value ->
            metadata["nativeModelKind"] = value.modelKind.toString()
            metadata["nativeSpeakerEmbeddingDim"] = value.speakerEmbeddingDim.toString()
            metadata["nativeSpeakerCount"] = value.speakerCount.toString()
            metadata["nativeSupportsCloning"] = value.supportsCloning.toString()
            metadata["nativeSupportsNamedSpeakers"] = value.supportsNamedSpeakers.toString()
            metadata["nativeSupportsInstruction"] = value.supportsInstruction.toString()
        }
        return metadata
    }

    /** Returns the durable app-managed location for captured voice artifacts. */
    fun durableVoiceArtifactDirectory(appDirectory: File? = null): File =
        File(appDirectory ?: File(System.getProperty("user.home"), APP_DIRECTORY), VOICE_ARTIFACT_DIRECTORY)

    fun sha256File(path: String): String = sha256File(File(path))

    fun sha256File(file: File): String {
        require(file.isFile) { "Artifact does not exist: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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

    fun textFingerprint(texts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        texts.forEach { text ->
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileArtifactMetadata(prefix: String, file: File?): Map<String, String> {
        val resolved = requireNotNull(file) {
            "Cannot fingerprint the selected artifact: file was not resolved"
        }
        require(resolved.isFile) {
            "Cannot fingerprint the selected artifact: ${resolved.absolutePath}"
        }
        val normalized = resolved.absoluteFile.normalize()
        return linkedMapOf(
            "${prefix}FingerprintAlgorithm" to "SHA-256",
            "${prefix}Name" to normalized.name,
            "${prefix}Path" to normalized.path,
            "${prefix}SizeBytes" to normalized.length().toString(),
            "${prefix}Sha256" to sha256File(normalized)
        )
    }

    private fun resolveModelFile(modelDir: String, modelName: String?): File? {
        val directory = File(modelDir).absoluteFile.normalize()
        val requested = modelName?.trim().takeUnless { it.isNullOrEmpty() }
        requested?.let { exact ->
            File(directory, exact).takeIf { it.isFile }?.let { return it }
        }
        val candidates = directory.listFiles { file ->
            file.isFile && file.extension.equals("gguf", ignoreCase = true) &&
                (requested == null || file.name.startsWith(requested, ignoreCase = true))
        }?.sortedBy { it.name.lowercase() }.orEmpty()
        return candidates.singleOrNull()
    }

    private fun sha256Text(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

}
