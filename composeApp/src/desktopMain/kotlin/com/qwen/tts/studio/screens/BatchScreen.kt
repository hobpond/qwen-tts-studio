package com.qwen.tts.studio.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.BatchChunkStatus
import com.qwen.tts.studio.batch.BatchManifest
import com.qwen.tts.studio.batch.TextBatching
import com.qwen.tts.studio.viewmodel.SettingsViewModel
import com.qwen.tts.studio.viewmodel.StudioViewModel
import com.qwen.tts.studio.viewmodel.VoiceCloneMode
import com.qwen.tts.studio.viewmodel.VoicesViewModel
import io.github.vinceglb.filekit.core.PickerType
import io.github.vinceglb.filekit.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.compose.rememberFileSaverLauncher
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@Composable
fun BatchScreen(
    viewModel: StudioViewModel,
    settingsViewModel: SettingsViewModel,
    voicesViewModel: VoicesViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val modelDir by settingsViewModel.modelDir.collectAsState()
    val modelName by settingsViewModel.modelName.collectAsState()
    val backendPreference by settingsViewModel.backendPreference.collectAsState()
    val voices by voicesViewModel.voices.collectAsState()
    val batchState by viewModel.batchState.collectAsState()
    val selectedVoicePreset = voices.firstOrNull { it.name == uiState.selectedVoice }
    val useNamedSpeaker = uiState.supportsNamedSpeakers && uiState.selectedSpeaker.isNotBlank()
    val speakerEmbeddingPath = if (
        uiState.supportsCloning && !useNamedSpeaker && uiState.voiceCloneMode == VoiceCloneMode.SpeakerEmbedding
    ) voicesViewModel.speakerEmbeddingForVoice(uiState.selectedVoice, uiState.speakerEmbeddingDim) else null
    val iclPromptPath = if (
        uiState.supportsCloning && !useNamedSpeaker && uiState.voiceCloneMode == VoiceCloneMode.IclPrompt
    ) voicesViewModel.iclPromptForVoice(uiState.selectedVoice, uiState.speakerEmbeddingDim) else null
    val missingSpeakerEmbedding = uiState.supportsCloning && !useNamedSpeaker &&
        uiState.voiceCloneMode == VoiceCloneMode.SpeakerEmbedding && selectedVoicePreset?.isSystem == false &&
        uiState.speakerEmbeddingDim > 0 && speakerEmbeddingPath == null
    val missingIclPrompt = uiState.supportsCloning && !useNamedSpeaker &&
        uiState.voiceCloneMode == VoiceCloneMode.IclPrompt && selectedVoicePreset?.isSystem == false &&
        uiState.speakerEmbeddingDim > 0 && iclPromptPath == null
    val batchVoiceEmbeddingPath = uiState.reusableVoiceSnapshot?.speakerEmbeddingPath ?: speakerEmbeddingPath
    var batchTextFile by remember { mutableStateOf("") }
    var batchTexts by remember { mutableStateOf(emptyList<String>()) }
    var batchOutputDirectory by remember { mutableStateOf("") }
    var batchReplayRequest by remember { mutableStateOf<com.qwen.tts.studio.batch.BatchGenerationRequest?>(null) }
    var batchRegenerateSelection by remember { mutableStateOf("") }
    var batchManifest by remember { mutableStateOf<BatchManifest?>(null) }
    var batchManifestPath by remember { mutableStateOf<File?>(null) }
    var batchSourceFile by remember { mutableStateOf<File?>(null) }

    val batchTextPicker = rememberFilePickerLauncher(
        type = PickerType.File(extensions = listOf("txt", "text")), title = "Select Batch Text File"
    ) { file ->
        file?.path?.let { path ->
            runCatching { TextBatching.packParagraphs(Files.readString(File(path).toPath(), StandardCharsets.UTF_8)) }
                .onSuccess { texts ->
                    batchTextFile = path
                    batchTexts = texts
                    batchSourceFile = File(path)
                    if (batchOutputDirectory.isBlank()) batchOutputDirectory = File(path).absoluteFile.parentFile?.path.orEmpty()
                    batchReplayRequest = null
                    batchManifest = null
                    batchManifestPath = null
                }
                .onFailure { error ->
                    batchTextFile = ""
                    batchTexts = emptyList()
                    viewModel.reportBatchError("Could not read batch text file: ${error.message ?: "unknown error"}")
                }
        }
    }
    val batchManifestPicker = rememberFilePickerLauncher(
        type = PickerType.File(extensions = listOf("json")), title = "Load Batch Manifest"
    ) { file ->
        file?.path?.let { path ->
            runCatching { viewModel.loadBatchManifest(File(path)) }
                .onSuccess { request ->
                    batchReplayRequest = request
                    batchRegenerateSelection = ""
                    batchManifestPath = File(path)
                    batchSourceFile = null
                    batchManifest = BatchAudioStore(request.outputDirectory).loadManifest(File(path).toPath())
                    batchTextFile = "Manifest: $path"
                    batchTexts = request.texts
                    batchOutputDirectory = request.outputDirectory.toString()
                }
                .onFailure { error -> viewModel.reportBatchError("Could not load batch manifest: ${error.message ?: "unknown error"}") }
        }
    }
    val batchDirectoryPicker = rememberDirectoryPickerLauncher(title = "Select Batch Output Directory") { directory ->
        directory?.path?.let {
            batchOutputDirectory = it
            batchReplayRequest = null
            batchManifest = null
            batchManifestPath = null
            batchSourceFile = null
        }
    }
    val batchRecombineSaver = rememberFileSaverLauncher { file ->
        file?.path?.let { output ->
            batchManifestPath?.let { viewModel.recombineBatchManifest(it, File(output)) }
                ?: viewModel.recombineBatchToFile(File(output))
        }
    }
    val asrModelFile = File(modelDir, SettingsViewModel.ASR_MODEL_NAME)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Batch generation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Generate, resume, validate, regenerate, and combine ordered text chunks without tying the workflow to Synthesis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (batchState.isRunning) {
                    LinearProgressIndicator(
                        progress = { if (batchState.total > 0) batchState.completed.toFloat() / batchState.total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${batchState.completed} of ${batchState.total} chunks complete", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Elapsed ${formatBatchDuration(batchState.elapsedMillis)} · " +
                            (batchState.estimatedRemainingMillis?.let { "about ${formatBatchDuration(it)} remaining" } ?: "estimating remaining time..."),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(batchTextFile, {}, readOnly = true, label = { Text("Text file or manifest") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedButton(onClick = { batchTextPicker.launch() }, enabled = !batchState.isRunning) { Text("Browse") }
                    OutlinedButton(onClick = { batchManifestPicker.launch() }, enabled = !batchState.isRunning) { Text("Load manifest") }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                viewModel.generateBatchManifest(File(batchOutputDirectory), batchTexts, modelDir, modelName, backendPreference, batchVoiceEmbeddingPath, iclPromptPath)
                            }.onSuccess { request ->
                                batchReplayRequest = request
                                batchManifestPath = File(batchOutputDirectory).resolve("manifest.json")
                                batchManifest = BatchAudioStore(request.outputDirectory).loadManifest(batchManifestPath!!.toPath())
                                batchTextFile = "Manifest: ${batchManifestPath!!.path}"
                            }.onFailure { error -> viewModel.reportBatchError("Could not generate manifest: ${error.message ?: "unknown error"}") }
                        },
                        enabled = !batchState.isRunning && batchTextFile.isNotBlank() && batchTexts.isNotEmpty()
                    ) { Text("Generate manifest") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(batchOutputDirectory, { batchOutputDirectory = it }, label = { Text("Output directory") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedButton(onClick = { batchDirectoryPicker.launch() }, enabled = !batchState.isRunning) {
                        androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Browse")
                    }
                }
                if (batchReplayRequest != null) {
                    OutlinedTextField(
                        batchRegenerateSelection, { batchRegenerateSelection = it },
                        label = { Text("Chunks to regenerate") },
                        supportingText = { Text("Blank to resume; e.g. 0, 3-5") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !batchState.isRunning
                    )
                }
                (batchManifest ?: batchState.result?.manifest)?.let { manifest ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Batch parts (${manifest.expectedChunkCount})", style = MaterialTheme.typography.labelLarge)
                        manifest.chunks.sortedBy { it.index }.forEach { chunk ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${chunk.index}: ${chunk.status} · ${chunk.text.replace("\n", " ").take(90)}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                if (batchReplayRequest != null && !batchState.isRunning) {
                                    OutlinedButton(onClick = {
                                        val replay = batchReplayRequest ?: return@OutlinedButton
                                        viewModel.startBatchGeneration(replay.copy(regenerateIndices = if (chunk.status == BatchChunkStatus.COMPLETE) setOf(chunk.index) else emptySet(), onlyIndices = setOf(chunk.index)))
                                    }) { Text(if (chunk.status == BatchChunkStatus.COMPLETE) "Regenerate" else "Generate") }
                                }
                            }
                        }
                        if (manifest.chunks.size == manifest.expectedChunkCount && manifest.chunks.all { it.status == BatchChunkStatus.COMPLETE } && !batchState.isRunning) {
                            OutlinedButton(onClick = { batchRecombineSaver.launch(baseName = "batch-combined", extension = "wav") }) { Text("Combine") }
                        }
                        batchManifestPath?.let { path ->
                            OutlinedButton(onClick = { viewModel.validateBatchManifest(path, batchSourceFile) }) { Text("Validate") }
                            OutlinedButton(onClick = { viewModel.validateBatchManifestWithAsr(path, asrModelFile, batchSourceFile) }, enabled = asrModelFile.isFile) { Text("Validate with ASR") }
                            Text(if (asrModelFile.isFile) "ASR model installed by Setup" else "Install the ASR model from Model Settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        batchState.validationReport?.let { report ->
                            Text("Validation: ${report.errors.size} errors, ${report.warnings.size} warnings", style = MaterialTheme.typography.bodySmall)
                            if (report.asrFindings.isNotEmpty()) Text("ASR: ${report.asrFindings.size} windows checked", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (batchTexts.isEmpty()) "No batch text loaded" else "${batchTexts.size} text chunks loaded", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (batchState.isRunning) {
                        OutlinedButton(onClick = viewModel::cancelBatchGeneration) { Text("Cancel") }
                    } else {
                        Button(onClick = {
                            val replay = batchReplayRequest
                            if (replay != null) {
                                runCatching { parseChunkSelection(batchRegenerateSelection, replay.texts.size) }
                                    .onSuccess { viewModel.startBatchGeneration(replay.copy(regenerateIndices = it)) }
                                    .onFailure { viewModel.reportBatchError(it.message ?: "Invalid chunk selection.") }
                            } else {
                                viewModel.startBatchGeneration(modelDir, modelName, batchVoiceEmbeddingPath, iclPromptPath, backendPreference, batchTexts, File(batchOutputDirectory), uiState.reusableVoiceSnapshot)
                            }
                        }, enabled = batchTexts.isNotEmpty() && batchOutputDirectory.isNotBlank() && !uiState.isGenerating && !batchState.isRunning && (batchReplayRequest != null || !missingSpeakerEmbedding) && (batchReplayRequest != null || !missingIclPrompt)) {
                            Text(if (batchReplayRequest != null && batchRegenerateSelection.isNotBlank()) "Regenerate selected" else if (batchReplayRequest != null) "Continue batch" else "Start batch")
                        }
                    }
                }
                if (batchState.isRunning || batchState.result != null || batchState.error != null) {
                    Text(
                        when {
                            batchState.isRunning -> "Generating chunk ${batchState.completed + 1} of ${batchState.total}..."
                            batchState.error != null -> "Batch failed: ${batchState.error}"
                            batchState.recombineError != null -> batchState.recombineError ?: "Recombination failed."
                            batchState.isRecombining -> "Recombining WAV..."
                            batchState.combinedFile != null -> "Combined WAV written: ${batchState.combinedFile?.name}"
                            else -> "Batch complete: ${batchState.completed} chunks written with manifest.json."
                        }, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatBatchDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return if (totalSeconds >= 60) "${totalSeconds / 60}m %02ds".format(totalSeconds % 60) else "${totalSeconds}s"
}

private fun parseChunkSelection(selection: String, total: Int): Set<Int> {
    if (selection.isBlank()) return emptySet()
    val result = sortedSetOf<Int>()
    selection.split(',').forEach { token ->
        val bounds = token.trim().split('-', limit = 2).map(String::trim)
        require(bounds.all { it.toIntOrNull() != null }) { "Invalid chunk selection: $token" }
        val first = bounds[0].toInt()
        val last = bounds.getOrElse(1) { bounds[0] }.toInt()
        require(first <= last && first >= 0 && last < total) { "Chunk selection must be between 0 and ${total - 1}." }
        result += first..last
    }
    return result
}
