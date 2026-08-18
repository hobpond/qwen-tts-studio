package com.qwen.tts.studio.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.qwen.tts.studio.App
import com.qwen.tts.studio.AppViewModels
import com.qwen.tts.studio.Screen
import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.BatchEngine
import com.qwen.tts.studio.batch.BatchGenerationStatus
import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenEngine
import com.qwen.tts.studio.viewmodel.SettingsViewModel
import com.qwen.tts.studio.viewmodel.StudioViewModel
import com.qwen.tts.studio.viewmodel.VoicesViewModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Headed-in-process proof for the rendered Batch surface. It uses Compose
 * semantics actions, not pixels, OS input, file dialogs, or computer use.
 */
class HeadedBatchUiInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var root: Path
    private lateinit var output: Path
    private lateinit var source: Path
    private lateinit var studio: StudioViewModel
    private lateinit var settings: SettingsViewModel
    private lateinit var voices: VoicesViewModel

    @Before
    fun setUp() {
        root = Files.createTempDirectory("headed-batch-ui")
        output = Files.createDirectories(root.resolve("batch"))
        settings = SettingsViewModel(persistSettings = false).apply {
            dismissWelcome()
            setAppDir(root.resolve("app").toString())
            setModelDir(root.resolve("models").toString())
            setModelName("test.gguf")
        }
        studio = StudioViewModel { DeterministicBatchEngine() }
        voices = VoicesViewModel(root.resolve("app").toString())

        source = root.resolve("source.txt")
        Files.writeString(source, "The headed batch fixture is visible to the rendered surface.")
        val combined = root.resolve("combined.wav").toFile()
        val manifestFile = output.resolve("manifest.json").toFile()
        val fileActions = BatchScreenFileActions(
            pickTextFile = {
                studio.setBatchSource(
                    source.toString(),
                    listOf("The headed batch fixture is visible to the rendered surface."),
                    output.toString()
                )
            },
            loadManifest = {
                val request = studio.loadBatchManifest(manifestFile)
                studio.setBatchManifestWorkspace(
                    manifestFile,
                    request,
                    BatchAudioStore(request.outputDirectory).loadManifest(manifestFile.toPath())
                )
            },
            pickOutputDirectory = { studio.setBatchOutputDirectory(output.toString()) },
            saveCombined = { studio.recombineBatchManifest(manifestFile, combined) }
        )

        composeRule.setContent {
            App(
                isDarkMode = false,
                initialScreen = Screen.Studio,
                harnessViewModels = AppViewModels(settings, studio, voices),
                batchFileActions = fileActions
            )
        }
    }

    @After
    fun tearDown() {
        ViewModelStore().also { store ->
            store.put("settings", settings)
            store.put("studio", studio)
            store.put("voices", voices)
            store.clear()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun renderedBatchSurfaceDrivesTheCompleteWorkflow() {
        composeRule.onNodeWithTag(BatchUiTestTags.navigationBatch).performClick()
        composeRule.onNodeWithTag(BatchUiTestTags.surface).assertExists()

        composeRule.onNodeWithTag(BatchUiTestTags.pickText).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            studio.batchState.value.texts.isNotEmpty() &&
                studio.batchState.value.outputDirectory.isNotBlank()
        }
        composeRule.onNodeWithTag(BatchUiTestTags.start).assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            val state = studio.batchState.value
            state.result?.status == BatchGenerationStatus.COMPLETED && !state.isRunning
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BatchUiTestTags.operationStatus)
            .assertTextContains("complete", substring = true, ignoreCase = true)

        composeRule.onNodeWithTag(BatchUiTestTags.loadManifest).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            studio.batchState.value.manifest?.chunks?.all { it.status.name == "COMPLETE" } == true
        }

        composeRule.onNodeWithTag(BatchUiTestTags.validateAll).assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            studio.batchState.value.validationReport?.passed == true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BatchUiTestTags.validationSummary)
            .assertTextContains("0 errors", substring = true, ignoreCase = true)

        composeRule.onNodeWithTag(BatchUiTestTags.combine).assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            val state = studio.batchState.value
            state.combinedFile?.isFile == true || state.recombineError != null
        }
        assertTrue(
            studio.batchState.value.combinedFile?.isFile == true,
            studio.batchState.value.recombineError ?: "Combined WAV was not written."
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun largeBatchUsesLazyListAndSemanticSearchToReachDistantChunks() {
        val largeTexts = List(320) { index ->
            "Large batch fixture chunk $index. This text exists to exercise indexed navigation."
        }
        studio.setBatchSource(source.toString(), largeTexts, output.toString())

        composeRule.onNodeWithTag(BatchUiTestTags.navigationBatch).performClick()
        composeRule.onNodeWithTag(BatchUiTestTags.start).assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            val state = studio.batchState.value
            state.result?.let { result ->
                result.status == BatchGenerationStatus.COMPLETED &&
                    !state.isRunning &&
                    result.manifest.chunks.size == 320
            } == true
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BatchUiTestTags.chunkList).assertExists()
        composeRule.onNodeWithTag(BatchUiTestTags.chunkListSummary)
            .assertTextContains("Showing 320 of 320 chunks")
        // A distant row is not eagerly composed into the initial viewport.
        composeRule.onAllNodesWithTag(BatchUiTestTags.chunk(319)).assertCountEquals(0)

        composeRule.onNodeWithTag(BatchUiTestTags.chunkSearch).performTextInput("319")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BatchUiTestTags.chunkListSummary)
            .assertTextContains("Showing 1 of 320 chunks")
        composeRule.onNodeWithTag(BatchUiTestTags.chunk(319)).assertExists()
        composeRule.onAllNodesWithTag(BatchUiTestTags.chunk(0)).assertCountEquals(0)

        composeRule.onNodeWithTag(BatchUiTestTags.chunkSearch).performTextClearance()
        composeRule.onNodeWithTag(BatchUiTestTags.chunkFilter).performClick()
        composeRule.onNodeWithTag(BatchUiTestTags.chunkFilterOption("NeedsAttention")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BatchUiTestTags.chunkListSummary)
            .assertTextContains("Showing 320 of 320 chunks")
    }

    private class DeterministicBatchEngine : BatchEngine {
        override fun loadDetailed(
            modelDir: String,
            modelName: String?,
            backendPreference: NativeBackendPreference
        ) = QwenEngine.NativeOperationResult(success = true)

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
            val sampleCount = maxOf(8_000, text.length * 400)
            val samples = FloatArray(sampleCount) { index ->
                if (index % 80 < 40) 0.04f else -0.04f
            }
            return QwenEngine.NativeResult(samples, 24_000, true, null, 1L)
        }
    }
}
