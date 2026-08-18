package com.qwen.tts.studio.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.BatchChunkStatus
import com.qwen.tts.studio.batch.BatchGenerationRequest
import com.qwen.tts.studio.batch.BatchIdentity
import com.qwen.tts.studio.batch.BatchManifest
import com.qwen.tts.studio.batch.BatchVoiceMode
import com.qwen.tts.studio.batch.BatchVoiceParameters
import com.qwen.tts.studio.batch.TextBatching
import com.qwen.tts.studio.engine.QwenEngine
import com.qwen.tts.studio.viewmodel.SettingsViewModel
import com.qwen.tts.studio.viewmodel.StudioViewModel
import com.qwen.tts.studio.viewmodel.BatchChunkValidationState
import com.qwen.tts.studio.viewmodel.BatchIncrementalValidationSettings
import com.qwen.tts.studio.viewmodel.VoiceCloneMode
import com.qwen.tts.studio.viewmodel.VoicesViewModel
import io.github.vinceglb.filekit.core.PickerType
import io.github.vinceglb.filekit.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.compose.rememberFileSaverLauncher
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@Composable
fun BatchScreen(
    viewModel: StudioViewModel,
    settingsViewModel: SettingsViewModel,
    voicesViewModel: VoicesViewModel,
    fileActions: BatchScreenFileActions? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val modelDir by settingsViewModel.modelDir.collectAsState()
    val modelName by settingsViewModel.modelName.collectAsState()
    val availableModelNames by settingsViewModel.availableModelNames.collectAsState()
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
    val capturedVoice = uiState.reusableVoiceSnapshot
    val batchVoiceEmbeddingPath = capturedVoice?.speakerEmbeddingPath ?: speakerEmbeddingPath
    val capturedVoiceInvalid = capturedVoice != null && capturedVoice.speakerEmbeddingPath.isNullOrBlank()
    var batchModelSpeakers by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var validationJob by remember { mutableStateOf<Job?>(null) }
    var batchModelPromptOpen by remember { mutableStateOf(false) }
    var batchVoiceMenuOpen by remember { mutableStateOf(false) }

    val batchTextFile = batchState.textFile
    val batchTexts = batchState.texts
    val batchOutputDirectory = batchState.outputDirectory
    val batchReplayRequest = batchState.replayRequest
    val batchRegenerateSelection = batchState.regenerateSelection
    val batchManifest = batchState.manifest ?: batchState.result?.manifest
    val batchVoiceNames = batchManifest?.chunks?.associate { it.index to (it.voiceName ?: uiState.selectedVoice) }.orEmpty()
    val batchModelNames = batchManifest?.chunks?.associate { it.index to (it.modelName ?: batchReplayRequest?.modelName ?: modelName) }.orEmpty()
    val batchVoicePrompts = batchManifest?.chunks?.associate { it.index to (it.voicePrompt ?: batchReplayRequest?.instruction.orEmpty()) }.orEmpty()
    val batchManifestPath = batchState.manifestFile?.let(::File)
    val batchSourceFile = batchState.sourceFile?.let(::File)
    val allowIncompatibleReplacement = batchState.allowIncompatibleReplacement
    val batchVoiceName = batchState.batchVoiceName ?: uiState.selectedVoice
    val batchModelName = batchState.batchModelName ?: modelName
    val batchVoicePrompt = batchState.batchVoicePrompt ?: uiState.selectedInstruction
    var chunkSearch by remember(batchManifestPath, batchManifest?.batchId) { mutableStateOf("") }
    var chunkFilter by remember(batchManifestPath, batchManifest?.batchId) { mutableStateOf(BatchChunkFilter.All) }
    var chunkFilterMenuOpen by remember(batchManifestPath, batchManifest?.batchId) { mutableStateOf(false) }
    val chunkListState = rememberLazyListState()

    LaunchedEffect(validationJob) {
        val job = validationJob ?: return@LaunchedEffect
        job.join()
        if (validationJob === job) validationJob = null
    }
    DisposableEffect(Unit) {
        onDispose { validationJob?.cancel() }
    }

    val validationActive = validationJob?.isActive == true
    val fileWriteActive = batchState.isRunning || batchState.isRecombining
    val workflowBusy = validationActive || fileWriteActive

    // The manifest is an externalized lifecycle artifact. Keep the screen's
    // projections synchronized with atomic writes made by generation,
    // validation, or another process while this screen is visible.
    LaunchedEffect(batchManifestPath) {
        while (true) {
            batchManifestPath?.let { viewModel.refreshBatchManifestFromDisk(it) }
            delay(500)
        }
    }

    val batchTextPicker = rememberFilePickerLauncher(
        type = PickerType.File(extensions = listOf("txt", "text")), title = "Select Batch Text File"
    ) { file ->
        file?.path?.let { path ->
            runCatching {
                val plan = viewModel.batchMemoryPlan()
                val limit = plan.maxCharacters ?: error(plan.reason ?: "Insufficient observed memory for batch planning.")
                TextBatching.packParagraphsForGeneration(Files.readString(File(path).toPath(), StandardCharsets.UTF_8), limit)
            }
                .onSuccess { texts ->
                    viewModel.setBatchSource(path, texts, File(path).absoluteFile.parentFile?.path.orEmpty())
                }
                .onFailure { error ->
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
                    val manifestFile = File(path)
                    viewModel.setBatchManifestWorkspace(
                        manifestFile,
                        request,
                        BatchAudioStore(request.outputDirectory).loadManifest(manifestFile.toPath())
                    )
                }
                .onFailure { error -> viewModel.reportBatchError("Could not load batch manifest: ${error.message ?: "unknown error"}") }
        }
    }
    val batchDirectoryPicker = rememberDirectoryPickerLauncher(title = "Select Batch Output Directory") { directory ->
        directory?.path?.let {
            viewModel.setBatchOutputDirectory(it)
        }
    }
    val batchRecombineSaver = rememberFileSaverLauncher { file ->
        file?.path?.let { output ->
            batchManifestPath?.let { viewModel.recombineBatchManifest(it, File(output)) }
                ?: viewModel.recombineBatchToFile(File(output))
        }
    }
    val pickTextFile = fileActions?.pickTextFile ?: { batchTextPicker.launch() }
    val loadManifest = fileActions?.loadManifest ?: { batchManifestPicker.launch() }
    val pickOutputDirectory = fileActions?.pickOutputDirectory ?: { batchDirectoryPicker.launch() }
    val saveCombined = fileActions?.saveCombined
        ?: { batchRecombineSaver.launch(baseName = "batch-combined", extension = "wav") }
    val asrModelFile = File(modelDir, SettingsViewModel.ASR_MODEL_NAME)
    val incrementalBatchValidation = asrModelFile.takeIf { it.isFile }?.let {
        BatchIncrementalValidationSettings(
            asrModelFile = it,
            asrBackend = backendPreference,
            maxRetries = 1
        )
    }
    fun speakersForModel(model: String?): List<String> =
        if (model == modelName) uiState.availableSpeakers else batchModelSpeakers[model].orEmpty()
    val batchVoiceOptions = (listOf(uiState.selectedVoice) + voices.map { it.name } + speakersForModel(batchModelName)).distinct()
    fun batchVoiceParameters(name: String, model: String? = null, prompt: String? = null): BatchVoiceParameters {
        val resolvedModel = model ?: modelName
        val modelSpeakers = speakersForModel(resolvedModel)
        val persistedSpeaker = batchReplayRequest?.speaker?.takeIf { it.isNotBlank() }
        val activeSpeaker = uiState.selectedSpeaker.takeIf {
            resolvedModel == modelName && uiState.supportsNamedSpeakers && it.isNotBlank()
        }
        val isNamedSpeaker = name in modelSpeakers
        val manifestCustomVoiceSpeaker = name.takeUnless {
            it.equals("Default Voice", ignoreCase = true) || it.endsWith("(Model)")
        }?.takeIf { resolvedModel?.contains("customvoice", ignoreCase = true) == true }
        val speaker = name.takeIf { isNamedSpeaker }
            ?: persistedSpeaker?.takeIf { name == it || name == "Default Voice" }
            ?: activeSpeaker
            ?: manifestCustomVoiceSpeaker
        val preset = voices.firstOrNull { it.name == name }
        val embedding = if (speaker == null && !isNamedSpeaker && uiState.supportsCloning && uiState.voiceCloneMode == VoiceCloneMode.SpeakerEmbedding) {
            if (name == uiState.selectedVoice && capturedVoice?.speakerEmbeddingPath != null) capturedVoice.speakerEmbeddingPath
            else voicesViewModel.speakerEmbeddingForVoice(name, uiState.speakerEmbeddingDim)
        } else null
        val icl = if (speaker == null && !isNamedSpeaker && uiState.supportsCloning && uiState.voiceCloneMode == VoiceCloneMode.IclPrompt) {
            voicesViewModel.iclPromptForVoice(name, uiState.speakerEmbeddingDim)
        } else null
        return BatchVoiceParameters(name = name, modelName = resolvedModel, voicePrompt = prompt, speakerEmbeddingPath = embedding, iclPromptPath = icl, speaker = speaker)
    }
    fun chunkVoiceParameters(manifest: BatchManifest): Map<Int, BatchVoiceParameters> =
        manifest.chunks.associate { chunk ->
            // Replay/regeneration must use the persisted per-chunk identity,
            // not the currently selected Studio defaults or capability cache.
            val name = chunk.voiceName ?: batchVoiceNames[chunk.index] ?: uiState.selectedVoice
            val model = chunk.modelName ?: batchModelNames[chunk.index] ?: modelName
            val prompt = chunk.voicePrompt ?: batchVoicePrompts[chunk.index]
            val parameters = batchVoiceParameters(name, model, prompt)
            val forcedNamedSpeaker = name.takeUnless {
                it.equals("Default Voice", ignoreCase = true) || it.endsWith("(Model)")
            }?.takeIf {
                batchReplayRequest?.voiceMode == BatchVoiceMode.NAMED_SPEAKER ||
                    model?.contains("customvoice", ignoreCase = true) == true
            }
            chunk.index to if (forcedNamedSpeaker != null && parameters.speaker == null) {
                parameters.copy(speaker = forcedNamedSpeaker)
            } else parameters
        }

    LaunchedEffect(batchManifest?.chunks?.map { it.modelName to it.voiceName }) {
        batchManifest?.chunks.orEmpty().mapNotNull { it.modelName }.distinct().forEach { manifestModel ->
            if (manifestModel != modelName && batchModelSpeakers[manifestModel] == null) {
                viewModel.loadBatchModelSpeakers(modelDir, manifestModel, backendPreference) { speakers ->
                    batchModelSpeakers = batchModelSpeakers + (manifestModel to speakers)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(BatchUiTestTags.surface)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Batch defaults", style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { batchModelPromptOpen = true }, enabled = !workflowBusy) { Text("M/P") }
                        DropdownMenu(expanded = batchModelPromptOpen, onDismissRequest = { batchModelPromptOpen = false }) {
                            Column(Modifier.width(420.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Model / prompt for the whole batch", style = MaterialTheme.typography.labelLarge)
                                availableModelNames.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(if (option == batchModelName) "✓ $option" else option) },
                                        onClick = {
                                            viewModel.loadBatchModelSpeakers(modelDir, option, backendPreference) { speakers -> batchModelSpeakers = batchModelSpeakers + (option to speakers) }
                                            viewModel.setBatchVoiceDefaults(batchVoiceName, option, batchVoicePrompt)
                                        }
                                    )
                                }
                                OutlinedTextField(
                                    value = batchVoicePrompt,
                                    onValueChange = { viewModel.setBatchVoiceDefaults(batchVoiceName, batchModelName, it) },
                                    label = { Text("Voice prompt (optional)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !workflowBusy
                                )
                                Text("These defaults apply to new chunks. Use Apply to all chunks to replace existing per-chunk settings.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { batchVoiceMenuOpen = true }, enabled = !workflowBusy) {
                            Text(batchVoiceName.removeSuffix(" (Model)"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = batchVoiceMenuOpen, onDismissRequest = { batchVoiceMenuOpen = false }) {
                            batchVoiceOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(if (option == batchVoiceName) "✓ ${option.removeSuffix(" (Model)")}" else option.removeSuffix(" (Model)")) },
                                    onClick = { viewModel.setBatchVoiceDefaults(option, batchModelName, batchVoicePrompt); batchVoiceMenuOpen = false }
                                )
                            }
                        }
                    }
                    batchManifestPath?.let { manifestFile ->
                        OutlinedButton(
                            onClick = {
                                runCatching { viewModel.applyBatchVoiceDefaults(manifestFile, batchVoiceName, batchModelName, batchVoicePrompt) }
                                    .onFailure { viewModel.reportBatchError("Could not apply batch defaults: ${it.message ?: "unknown error"}") }
                            },
                            enabled = !workflowBusy
                        ) { Text("Apply to all chunks") }
                    }
                }
                if (uiState.reusableVoiceSnapshot != null || uiState.hasAudio) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            when {
                                capturedVoiceInvalid -> "The kept preview voice has no reusable conditioning artifact."
                                uiState.reusableVoiceSnapshot != null -> "Reusable preview voice is ready for batch generation."
                                uiState.supportsCloning -> "A completed preview can be captured for batch reuse."
                                else -> "This model cannot capture a reusable preview voice."
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (capturedVoiceInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.reusableVoiceSnapshot != null) {
                            OutlinedButton(onClick = viewModel::clearCapturedVoice, enabled = !workflowBusy) {
                                Text("Clear kept voice")
                            }
                        } else if (uiState.hasAudio && uiState.supportsCloning) {
                            OutlinedButton(
                                onClick = { viewModel.captureCurrentVoiceForBatch(modelDir, modelName, backendPreference) },
                                enabled = !workflowBusy && !uiState.isGenerating && !uiState.isCapturingVoice
                            ) { Text(if (uiState.isCapturingVoice) "Capturing..." else "Keep preview voice") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(batchTextFile, {}, readOnly = true, label = { Text("Text file or manifest") }, modifier = Modifier.weight(1f).testTag(BatchUiTestTags.textFile), singleLine = true)
                    OutlinedButton(onClick = pickTextFile, modifier = Modifier.testTag(BatchUiTestTags.pickText), enabled = !workflowBusy) { Text("Browse") }
                    OutlinedButton(onClick = loadManifest, modifier = Modifier.testTag(BatchUiTestTags.loadManifest), enabled = !workflowBusy) { Text("Load manifest") }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                viewModel.generateBatchManifest(File(batchOutputDirectory), batchTexts, modelDir, batchModelName, backendPreference, batchVoiceEmbeddingPath, iclPromptPath, batchVoicePrompt, batchVoiceName)
                            }.onSuccess { request ->
                                val manifestFile = File(batchOutputDirectory).resolve("manifest.json")
                                viewModel.setGeneratedBatchManifest(
                                    manifestFile,
                                    request,
                                    BatchAudioStore(request.outputDirectory).loadManifest(manifestFile.toPath())
                                )
                            }.onFailure { error -> viewModel.reportBatchError("Could not generate manifest: ${error.message ?: "unknown error"}") }
                        },
                        modifier = Modifier.testTag(BatchUiTestTags.generateManifest),
                        enabled = !workflowBusy && !capturedVoiceInvalid && batchTextFile.isNotBlank() && batchTexts.isNotEmpty()
                    ) { Text("Generate manifest") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(batchOutputDirectory, viewModel::setBatchOutputDirectory, label = { Text("Output directory") }, modifier = Modifier.weight(1f).testTag(BatchUiTestTags.outputDirectory), singleLine = true)
                    OutlinedButton(onClick = pickOutputDirectory, modifier = Modifier.testTag(BatchUiTestTags.pickOutputDirectory), enabled = !workflowBusy) {
                        androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Browse")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = allowIncompatibleReplacement,
                        onCheckedChange = viewModel::setBatchReplacementAllowed,
                        enabled = !workflowBusy && batchReplayRequest == null
                    )
                    Text("Allow replacing an incompatible existing batch", style = MaterialTheme.typography.bodySmall)
                }
                if (batchReplayRequest != null) {
                    OutlinedTextField(
                        batchRegenerateSelection, viewModel::setBatchRegenerateSelection,
                        label = { Text("Chunks to regenerate") },
                        supportingText = { Text("Blank to resume; e.g. 0, 3-5") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !workflowBusy
                    )
                }
                if (batchState.isRunning) {
                    LinearProgressIndicator(
                        progress = { if (batchState.total > 0) batchState.generated.toFloat() / batchState.total else 0f },
                        modifier = Modifier.fillMaxWidth().testTag(BatchUiTestTags.progress)
                    )
                    Text(
                        if (batchState.generated > batchState.completed) {
                            "${batchState.completed} of ${batchState.total} chunks persisted · ${batchState.validated} validated · ${batchState.generated} generated"
                        } else {
                            "${batchState.completed} of ${batchState.total} chunks persisted · ${batchState.validated} validated"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Elapsed ${formatBatchDuration(batchState.elapsedMillis)} · " +
                            (batchState.estimatedRemainingMillis?.let { "about ${formatBatchDuration(it)} remaining" } ?: "estimating remaining time..."),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (batchState.isRunning || batchState.result != null || batchState.error != null) {
                    Text(
                        when {
                            batchState.isRunning -> "Batch operation: generating chunk ${batchState.currentIndex.coerceAtLeast(0) + 1} of ${batchState.total}"
                            batchState.error != null -> "Batch operation failed: ${batchState.error}"
                            batchState.recombineError != null -> batchState.recombineError ?: "Recombination failed."
                            batchState.isRecombining -> "Batch operation: recombining WAV..."
                            batchState.combinedFile != null -> "Batch operation complete: ${batchState.combinedFile?.name}"
                            else -> "Batch operation complete: ${batchState.completed} chunks written."
                        },
                        modifier = Modifier
                            .testTag(BatchUiTestTags.operationStatus)
                            .semantics {
                                stateDescription = when {
                                    batchState.isRunning -> "running"
                                    batchState.isRecombining -> "recombining"
                                    batchState.error != null || batchState.recombineError != null -> "failed"
                                    batchState.combinedFile != null -> "combined"
                                    batchState.result != null -> "complete"
                                    else -> "idle"
                                }
                            },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (batchState.isRunning) {
                    Text(
                        "Generation is running; batch edits and file actions are paused until it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val operationManifest = batchManifest
                val operationManifestFile = batchManifestPath
                    ?: operationManifest?.let { File(batchOutputDirectory, "manifest.json") }
                if (operationManifest != null && operationManifestFile?.isFile == true) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Batch operations", style = MaterialTheme.typography.labelLarge)
                        Button(
                            onClick = {
                                validationJob = viewModel.validateAllBatchChunks(
                                    operationManifestFile,
                                    asrModelFile.takeIf { it.isFile },
                                    batchSourceFile,
                                    asrBackend = backendPreference
                                )
                            },
                            modifier = Modifier.testTag(BatchUiTestTags.validateAll),
                            enabled = !workflowBusy
                        ) { Text(if (validationActive) "Validating..." else "Validate all chunks") }
                        val hasFailedChunks = operationManifest.chunks.any {
                            it.status != BatchChunkStatus.COMPLETE || it.validationPassed == false
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    viewModel.rechunkFailedBatch(
                                        operationManifestFile,
                                        TextBatching.DEFAULT_RECHUNK_CHARACTERS
                                    )
                                }.onFailure {
                                    viewModel.reportBatchError("Could not rechunk failed chunks: ${it.message}")
                                }
                            },
                            modifier = Modifier.testTag(BatchUiTestTags.rechunkFailed),
                            enabled = !workflowBusy && hasFailedChunks
                        ) { Text("Rechunk failed (${TextBatching.DEFAULT_RECHUNK_CHARACTERS})") }
                        OutlinedButton(
                            onClick = {
                                batchReplayRequest?.let { request ->
                                    viewModel.startBatchGeneration(
                                        request.copy(
                                            regenerateIndices = emptySet(),
                                            onlyIndices = null,
                                            chunkVoices = chunkVoiceParameters(operationManifest)
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.testTag(BatchUiTestTags.resume),
                            enabled = !workflowBusy && batchReplayRequest != null
                        ) { Text("Resume batch") }
                        OutlinedButton(
                            onClick = {
                                batchReplayRequest?.let { request ->
                                    viewModel.startBatchGeneration(
                                        request.copy(
                                            regenerateIndices = operationManifest.chunks.map { it.index }.toSet(),
                                            onlyIndices = null,
                                            chunkVoices = chunkVoiceParameters(operationManifest)
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.testTag(BatchUiTestTags.regenerateAll),
                            enabled = !workflowBusy && batchReplayRequest != null
                        ) { Text("Regenerate all chunks") }
                        if (operationManifest.chunks.size == operationManifest.expectedChunkCount &&
                            operationManifest.chunks.all { it.status == BatchChunkStatus.COMPLETE }
                        ) {
                            OutlinedButton(
                                onClick = saveCombined,
                                modifier = Modifier.testTag(BatchUiTestTags.combine),
                                enabled = !workflowBusy
                            ) { Text("Combine") }
                        }
                    }
                }
                operationManifest?.let { manifest ->
                    val allChunks = manifest.chunks.sortedBy { it.index }
                    val normalizedChunkSearch = chunkSearch.trim()
                    val visibleChunks = allChunks.filter { chunk ->
                        val validation = batchState.chunkValidation[chunk.index]
                        val isValidated = validation?.passed ?: (chunk.validationPassed == true)
                        val matchesFilter = when (chunkFilter) {
                            BatchChunkFilter.All -> true
                            BatchChunkFilter.NeedsAttention ->
                                chunk.status != BatchChunkStatus.COMPLETE || !isValidated
                            BatchChunkFilter.Complete -> chunk.status == BatchChunkStatus.COMPLETE
                            BatchChunkFilter.Validated ->
                                chunk.status == BatchChunkStatus.COMPLETE && isValidated
                        }
                        val searchableText = buildString {
                            append(chunk.displayIndex)
                            append(' ')
                            append(chunk.index)
                            append(' ')
                            append(chunk.status.name)
                            append(' ')
                            append(chunk.voiceName.orEmpty())
                            append(' ')
                            append(chunk.text)
                        }
                        matchesFilter && (normalizedChunkSearch.isBlank() || searchableText.contains(normalizedChunkSearch, ignoreCase = true))
                    }
                    LaunchedEffect(normalizedChunkSearch, chunkFilter, manifest.batchId, allChunks.size) {
                        chunkListState.scrollToItem(0)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Batch parts (${manifest.expectedChunkCount})", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = chunkSearch,
                                onValueChange = { chunkSearch = it },
                                label = { Text("Find chunk, text, or voice") },
                                placeholder = { Text("e.g. 319 or a phrase") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag(BatchUiTestTags.chunkSearch),
                                enabled = !workflowBusy
                            )
                            Box {
                                OutlinedButton(
                                    onClick = { chunkFilterMenuOpen = true },
                                    modifier = Modifier.testTag(BatchUiTestTags.chunkFilter),
                                    enabled = !workflowBusy
                                ) { Text("Filter: ${chunkFilter.label}") }
                                DropdownMenu(
                                    expanded = chunkFilterMenuOpen,
                                    onDismissRequest = { chunkFilterMenuOpen = false }
                                ) {
                                    BatchChunkFilter.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(if (option == chunkFilter) "✓ ${option.label}" else option.label) },
                                            modifier = Modifier.testTag(BatchUiTestTags.chunkFilterOption(option.name)),
                                            onClick = {
                                                chunkFilter = option
                                                chunkFilterMenuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                            if (chunkSearch.isNotBlank() || chunkFilter != BatchChunkFilter.All) {
                                OutlinedButton(
                                    onClick = {
                                        chunkSearch = ""
                                        chunkFilter = BatchChunkFilter.All
                                    },
                                    enabled = !workflowBusy
                                ) { Text("Clear") }
                            }
                        }
                        Text(
                            "Showing ${visibleChunks.size} of ${allChunks.size} chunks",
                            modifier = Modifier
                                .testTag(BatchUiTestTags.chunkListSummary)
                                .semantics { stateDescription = "${visibleChunks.size} of ${allChunks.size} chunks shown" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("#", Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall)
                            Text("Voice / text", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            Text("Actions", Modifier.width(160.dp), style = MaterialTheme.typography.labelSmall)
                            Text("Validation", Modifier.width(88.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        LazyColumn(
                            state = chunkListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((visibleChunks.size * 80).coerceIn(96, 560).dp)
                                .testTag(BatchUiTestTags.chunkList),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(visibleChunks, key = { it.index }) { chunk ->
                                val validation = batchState.chunkValidation[chunk.index]
                                val manifestFile = batchManifestPath
                            val isSavingChunk = batchState.savingChunkIndex == chunk.index
                            var validationDetailsOpen by remember(chunk.index, validation) { mutableStateOf(false) }
                            var voiceMenuOpen by remember(chunk.index) { mutableStateOf(false) }
                            var textDetailsOpen by remember(chunk.index) { mutableStateOf(false) }
                            var configurationOpen by remember(chunk.index) { mutableStateOf(false) }
                            // A loaded manifest is authoritative for row configuration.
                            val chunkVoiceName = chunk.voiceName ?: uiState.selectedVoice
                            val chunkModelName = chunk.modelName ?: batchReplayRequest?.modelName ?: modelName
                             val chunkVoicePrompt = chunk.voicePrompt.orEmpty()
                             val chunkVoiceOptions = (listOf(chunkVoiceName, uiState.selectedVoice) + voices.map { it.name } + speakersForModel(chunkModelName)).distinct()
                            Row(Modifier.fillMaxWidth().testTag(BatchUiTestTags.chunk(chunk.index)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                 Box {
                                     OutlinedButton(onClick = { configurationOpen = true }, enabled = !workflowBusy) { Text("M/P") }
                                     DropdownMenu(expanded = configurationOpen, onDismissRequest = { configurationOpen = false }) {
                                        Column(Modifier.width(420.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Chunk ${chunk.displayIndex} configuration", style = MaterialTheme.typography.labelLarge)
                                            Text("Model", style = MaterialTheme.typography.labelSmall)
                                            availableModelNames.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(if (option == chunkModelName) "✓ $option" else option) },
                                                    onClick = {
                                                        configurationOpen = false
                                                        viewModel.loadBatchModelSpeakers(modelDir, option, backendPreference) { speakers -> batchModelSpeakers = batchModelSpeakers + (option to speakers) }
                                                        manifestFile?.let { path -> runCatching { viewModel.setBatchChunkVoice(path, chunk.index, chunkVoiceName, option, chunkVoicePrompt) }.onFailure { viewModel.reportBatchError("Could not update chunk model: ${it.message}") } }
                                                    }
                                                )
                                            }
                                            OutlinedTextField(
                                                value = chunkVoicePrompt,
                                                onValueChange = {
                                                    manifestFile?.let { path -> runCatching { viewModel.setBatchChunkVoice(path, chunk.index, chunkVoiceName, chunkModelName, it) }.onFailure { viewModel.reportBatchError("Could not update voice prompt: ${it.message}") } }
                                                },
                                                label = { Text("Voice prompt (optional)") },
                                                modifier = Modifier.fillMaxWidth(),
                                                enabled = !workflowBusy
                                            )
                                        }
                                     }
                                 }
                                 Box {
                                     val displayVoiceName = chunkVoiceName.removeSuffix(" (Model)")
                                     OutlinedButton(onClick = { voiceMenuOpen = true }, enabled = !workflowBusy) { Text(displayVoiceName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    DropdownMenu(expanded = voiceMenuOpen, onDismissRequest = { voiceMenuOpen = false }) {
                                        chunkVoiceOptions.forEach { option ->
                                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                                voiceMenuOpen = false
                                                manifestFile?.let { path -> runCatching { viewModel.setBatchChunkVoice(path, chunk.index, option) }.onFailure { viewModel.reportBatchError("Could not assign voice: ${it.message}") } }
                                            })
                                        }
                                    }
                                }
                                 Text("${chunk.displayIndex}: ${if (isSavingChunk) "SAVING" else chunk.status} · ${chunk.text.replace("\n", " ").take(90)}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                if (batchState.isRunning && batchState.currentIndex == chunk.index) {
                                    Column(Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("Generating...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                 }
                                Box {
                                    IconButton(onClick = { textDetailsOpen = true }) { Text("T", style = MaterialTheme.typography.labelMedium) }
                                    DropdownMenu(expanded = textDetailsOpen, onDismissRequest = { textDetailsOpen = false }) {
                                        Column(Modifier.width(420.dp).padding(12.dp)) {
                                            Text("Chunk ${chunk.displayIndex} text", style = MaterialTheme.typography.labelLarge)
                                            val isPlaying = batchState.playingChunkIndex == chunk.index
                                             val fraction = if (isPlaying && batchState.chunkPlaybackDurationSeconds > 0f) {
                                                 (batchState.chunkPlaybackPositionSeconds / batchState.chunkPlaybackDurationSeconds).coerceIn(0f, 1f)
                                             } else 0f
                                             val textLength = chunk.text.length
                                             val playbackSeconds = batchState.playingChunkWindowStartSeconds + batchState.chunkPlaybackPositionSeconds
                                             val alignedSpan = if (isPlaying) {
                                                 batchState.playingChunkAlignment?.spans?.lastOrNull { span ->
                                                     playbackSeconds >= span.startSeconds && playbackSeconds <= span.endSeconds
                                                 } ?: batchState.playingChunkAlignment?.spans?.lastOrNull { it.startSeconds <= playbackSeconds }
                                             } else null
                                             val highlightStart = alignedSpan?.startText?.coerceIn(0, textLength) ?: -1
                                             val highlightEnd = alignedSpan?.endText?.coerceIn(highlightStart.coerceAtLeast(0), textLength) ?: -1
                                             val annotated = buildAnnotatedString {
                                                 if (isPlaying && highlightStart < highlightEnd) {
                                                     append(chunk.text.substring(0, highlightStart))
                                                     withStyle(SpanStyle(background = Color(0x665DADE2))) { append(chunk.text.substring(highlightStart, highlightEnd)) }
                                                     append(chunk.text.substring(highlightEnd))
                                                 } else append(chunk.text)
                                             }
                                             Text(annotated, style = MaterialTheme.typography.bodySmall)
                                             if (isPlaying && batchState.playingChunkAlignment == null) {
                                                 Text("Approximate alignment unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                             } else if (isPlaying && batchState.playingChunkAlignment?.quality == "APPROXIMATE") {
                                                 Text("Estimated alignment", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                             }
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.playBatchChunk(manifestFile?.parentFile?.resolve(chunk.fileName) ?: File(batchOutputDirectory, chunk.fileName)) },
                                    enabled = chunk.status == BatchChunkStatus.COMPLETE && !validationActive
                                ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play chunk") }
                                IconButton(
                                    onClick = { viewModel.playBatchChunkWindow(manifestFile?.parentFile?.resolve(chunk.fileName) ?: File(batchOutputDirectory, chunk.fileName), true) },
                                    enabled = chunk.status == BatchChunkStatus.COMPLETE && !validationActive
                                ) { Text("P", style = MaterialTheme.typography.labelMedium) }
                                IconButton(
                                    onClick = { viewModel.playBatchChunkWindow(manifestFile?.parentFile?.resolve(chunk.fileName) ?: File(batchOutputDirectory, chunk.fileName), false) },
                                    enabled = chunk.status == BatchChunkStatus.COMPLETE && !validationActive
                                ) { Text("S", style = MaterialTheme.typography.labelMedium) }
                                if (batchState.playingChunkIndex == chunk.index) {
                                    Slider(
                                        value = batchState.chunkPlaybackPositionSeconds,
                                        onValueChange = { viewModel.seekBatchChunk(chunk.index, it) },
                                        valueRange = 0f..batchState.chunkPlaybackDurationSeconds.coerceAtLeast(0.01f),
                                        modifier = Modifier.width(140.dp)
                                    )
                                    OutlinedButton(onClick = viewModel::stopBatchChunk) { Text("Stop") }
                                }
                                 if (batchReplayRequest != null) {
                                     val isGeneratingChunk = batchState.isRunning && batchState.currentIndex == chunk.index
                                     OutlinedButton(
                                         onClick = {
                                             val replay = batchReplayRequest ?: return@OutlinedButton
                                             viewModel.startBatchGeneration(
                                                 replay.copy(regenerateIndices = if (chunk.status == BatchChunkStatus.COMPLETE) setOf(chunk.index) else emptySet(), onlyIndices = setOf(chunk.index), chunkVoices = chunkVoiceParameters(manifest)),
                                                 incrementalValidation = incrementalBatchValidation
                                             )
                                         },
                                         enabled = !workflowBusy
                                     ) {
                                         if (isGeneratingChunk) {
                                             Icon(Icons.Filled.HourglassEmpty, contentDescription = "Generating", tint = MaterialTheme.colorScheme.primary)
                                             Spacer(Modifier.width(6.dp))
                                             Text("Generating…")
                                         } else {
                                             Text(if (chunk.status == BatchChunkStatus.COMPLETE) "Regenerate" else "Generate")
                                         }
                                     }
                                 }
                                if (manifestFile != null) {
                                    IconButton(
                                        onClick = { validationJob = viewModel.validateBatchChunk(manifestFile, chunk.index, asrModelFile.takeIf { it.isFile }, batchSourceFile, asrBackend = backendPreference) },
                                        modifier = Modifier.testTag(BatchUiTestTags.validateChunk(chunk.index)),
                                        enabled = chunk.status == BatchChunkStatus.COMPLETE && !workflowBusy
                                    ) { Text("V", style = MaterialTheme.typography.titleMedium) }
                                }
                                Box {
                                     IconButton(
                                         onClick = { validationDetailsOpen = true },
                                         modifier = Modifier
                                             .testTag(BatchUiTestTags.validationState(chunk.index))
                                             .semantics {
                                                 stateDescription = when {
                                                     validation?.isRunning == true -> "running"
                                                     validation?.passed == true -> "passed"
                                                     validation?.passed == false -> "failed"
                                                     else -> "pending"
                                                 }
                                             },
                                         enabled = validation?.passed == false
                                     ) {
                                        when {
                                            validation?.isRunning == true -> Icon(Icons.Filled.HourglassEmpty, "Validation running", tint = MaterialTheme.colorScheme.primary)
                                            validation?.passed == true -> Icon(Icons.Filled.CheckCircle, "Validation passed", tint = androidx.compose.ui.graphics.Color(0xFF2E7D32))
                                            validation?.passed == false -> Icon(Icons.Filled.Error, "Validation failed", tint = MaterialTheme.colorScheme.error)
                                            else -> Text("-", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    DropdownMenu(expanded = validationDetailsOpen, onDismissRequest = { validationDetailsOpen = false }) {
                                         DropdownMenuItem(
                                             text = { Text("Validation failed") },
                                             onClick = { validationDetailsOpen = false }, enabled = false
                                         )
                                         validation?.message?.let { message ->
                                             DropdownMenuItem(text = { Text("Failure: $message", maxLines = 4, overflow = TextOverflow.Ellipsis) }, onClick = { validationDetailsOpen = false }, enabled = false)
                                         }
                                         batchState.validationReport?.asrFindings?.filter { it.chunkIndex == chunk.index && !it.passed }?.forEach { finding ->
                                             DropdownMenuItem(
                                                 text = {
                                                     Text(
                                                         buildString {
                                                             append("${finding.window.name} failure\n")
                                                             append("Expected: ${finding.expectedText.ifBlank { "(unavailable)" }}\n")
                                                             append("Actual: ${finding.transcript ?: finding.error ?: "(unavailable)"}")
                                                         },
                                                         maxLines = 8,
                                                         overflow = TextOverflow.Ellipsis
                                                     )
                                                 },
                                                 onClick = { validationDetailsOpen = false }, enabled = false
                                             )
                                        }
                                    }
                                }
                            }
                            }
                        }
                        batchManifestPath?.let { path ->
                            Text(if (asrModelFile.isFile) "ASR model installed by Setup" else "Install the ASR model from Model Settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (validationActive) {
                            Text("Validation is running; batch file writes are paused until it finishes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        batchState.validationReport?.let { report ->
                            Text("Validation: ${report.errors.size} errors, ${report.warnings.size} warnings", modifier = Modifier.testTag(BatchUiTestTags.validationSummary), style = MaterialTheme.typography.bodySmall)
                            if (false && report.asrFindings.isNotEmpty()) {
                                Text("ASR findings (${report.asrFindings.size})", style = MaterialTheme.typography.labelLarge)
                                report.asrFindings.forEach { finding ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            val window = finding.window.name.lowercase(Locale.ROOT)
                                            val outcome = if (finding.passed) "PASS" else "FAIL"
                                            Text("$outcome · Chunk ${finding.chunkIndex} · $window", style = MaterialTheme.typography.labelMedium)
                                            Text("Expected: ${finding.expectedText.ifBlank { "(none)" }}", style = MaterialTheme.typography.bodySmall)
                                            Text("Transcript: ${finding.transcript ?: "(unavailable)"}", style = MaterialTheme.typography.bodySmall)
                                            Text("Score: ${finding.score?.let { "%.3f".format(Locale.ROOT, it) } ?: "n/a"}", style = MaterialTheme.typography.bodySmall)
                                            finding.error?.let { error ->
                                                Text("Error: $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
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
                                    .onSuccess {
                                        viewModel.startBatchGeneration(
                                            replay.copy(regenerateIndices = it, chunkVoices = batchState.manifest?.let(::chunkVoiceParameters) ?: replay.chunkVoices),
                                            incrementalValidation = incrementalBatchValidation
                                        )
                                    }
                                    .onFailure { viewModel.reportBatchError(it.message ?: "Invalid chunk selection.") }
                            } else {
                                val defaultBatchVoice = batchVoiceParameters(batchVoiceName, batchModelName, batchVoicePrompt)
                                val selectedSpeaker = defaultBatchVoice.speaker
                                val effectiveIcl = defaultBatchVoice.iclPromptPath.takeIf { capturedVoice == null && uiState.supportsCloning && selectedSpeaker == null }
                                val effectiveEmbedding = defaultBatchVoice.speakerEmbeddingPath.takeIf {
                                    uiState.supportsCloning && selectedSpeaker == null && effectiveIcl == null
                                }
                                val instruction = batchVoicePrompt.takeIf {
                                    capturedVoice == null && it.isNotBlank()
                                }
                                viewModel.startBatchGeneration(
                                    BatchGenerationRequest(
                                        batchId = "batch-${System.currentTimeMillis()}",
                                        modelDir = modelDir,
                                        modelName = batchModelName.trim().takeUnless { it.isEmpty() },
                                        backendPreference = backendPreference,
                                        texts = batchTexts,
                                        languageId = QwenEngine.mapLanguageToId(uiState.selectedLanguage),
                                        instruction = instruction,
                                        speaker = selectedSpeaker,
                                        speakerEmbeddingPath = effectiveEmbedding,
                                        iclPromptPath = effectiveIcl,
                                        voiceProvenance = capturedVoice?.sourceInstruction,
                                        voiceMode = when {
                                            capturedVoice != null -> BatchVoiceMode.CAPTURED_VOICE
                                            selectedSpeaker != null -> BatchVoiceMode.NAMED_SPEAKER
                                            !effectiveIcl.isNullOrBlank() -> BatchVoiceMode.ICL_PROMPT
                                            !effectiveEmbedding.isNullOrBlank() -> BatchVoiceMode.SPEAKER_EMBEDDING
                                            else -> BatchVoiceMode.MODEL_DEFAULT
                                        },
                                        speakerEmbeddingSha256 = capturedVoice?.speakerEmbeddingSha256
                                            ?: BatchIdentity.sha256FileOrNull(effectiveEmbedding),
                                        referenceWavPath = capturedVoice?.referenceWavPath,
                                        referenceWavSha256 = capturedVoice?.referenceWavSha256,
                                        iclPromptSha256 = BatchIdentity.sha256FileOrNull(effectiveIcl),
                                        outputDirectory = File(batchOutputDirectory).toPath(),
                                        allowIncompatibleReplacement = allowIncompatibleReplacement,
                                        allowAdaptiveRechunking = true,
                                        defaultVoiceName = batchVoiceName,
                                        chunkVoices = batchTexts.indices.associateWith { index -> batchVoiceParameters(batchVoiceNames[index] ?: batchVoiceName, batchModelNames[index] ?: batchModelName, batchVoicePrompts[index] ?: instruction) }
                                    ),
                                    incrementalValidation = incrementalBatchValidation
                                )
                            }
                        }, modifier = Modifier.testTag(BatchUiTestTags.start), enabled = batchTexts.isNotEmpty() && batchOutputDirectory.isNotBlank() && !capturedVoiceInvalid && !uiState.isGenerating && !workflowBusy && (batchReplayRequest != null || !missingSpeakerEmbedding) && (batchReplayRequest != null || !missingIclPrompt)) {
                            Text(if (batchReplayRequest != null && batchRegenerateSelection.isNotBlank()) "Regenerate selected" else if (batchReplayRequest != null) "Continue batch" else "Start batch")
                        }
                    }
                }
                if (false && (batchState.isRunning || batchState.result != null || batchState.error != null)) {
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

private enum class BatchChunkFilter(val label: String) {
    All("All"),
    NeedsAttention("Needs attention"),
    Complete("Complete"),
    Validated("Validated")
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
