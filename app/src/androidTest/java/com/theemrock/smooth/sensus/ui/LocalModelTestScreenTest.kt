package com.theemrock.smooth.sensus.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalModelTestScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun requiredControls_areDisplayed_andInvokeActions() {
        var selectCount = 0
        var runCount = 0
        var grammarCount = 0
        var unloadCount = 0

        composeRule.setContent {
            LocalModelTestScreen(
                state = LocalModelUiState(
                    phase = LocalModelPhase.READY,
                    selectedFilename = "model.gguf",
                    modelSizeBytes = 1024,
                    hasActiveModel = true,
                    status = "Model ready",
                    rawOutput = "raw output"
                ),
                prompt = "test prompt",
                onPromptChange = {},
                onSelectModel = { selectCount++ },
                onLoadSavedModel = {},
                onRun = { runCount++ },
                onGrammarSmoke = { grammarCount++ },
                onUnload = { unloadCount++ }
            )
        }

        composeRule.onNodeWithText("model.gguf").assertIsDisplayed()
        composeRule.onNodeWithText("1.0 KiB · Hazır").assertIsDisplayed()
        composeRule.onNodeWithText("Değiştir").performClick()
        composeRule.onNodeWithText("Çalıştır").performClick()
        composeRule.onNodeWithText("Gramer Testi").performClick()
        composeRule.onNodeWithText("Kapat").performClick()
        composeRule.onNodeWithText("raw output").performScrollTo().assertIsDisplayed()

        assertEquals(1, selectCount)
        assertEquals(1, runCount)
        assertEquals(1, grammarCount)
        assertEquals(1, unloadCount)
    }

    @Test
    fun busyState_disablesActions_andShowsCopyProgress() {
        composeRule.setContent {
            LocalModelTestScreen(
                state = LocalModelUiState(
                    phase = LocalModelPhase.COPYING,
                    selectedFilename = "large.gguf",
                    copiedBytes = 25,
                    copyTotalBytes = 100,
                    hasActiveModel = true,
                    status = "Copying model"
                ),
                prompt = "prompt",
                onPromptChange = {},
                onSelectModel = {},
                onLoadSavedModel = {},
                onRun = {},
                onGrammarSmoke = {},
                onUnload = {}
            )
        }

        composeRule.onNodeWithText("Copying model").assertIsDisplayed()
        composeRule.onNodeWithText("25 B / 100 B").assertIsDisplayed()
        composeRule.onNodeWithText("Değiştir").assertIsNotEnabled()
        composeRule.onNodeWithText("Çalıştır").assertIsNotEnabled()
        composeRule.onNodeWithText("Gramer Testi").assertIsNotEnabled()
    }

    @Test
    fun noActiveModel_allowsSelection_andOptionalStoredLoad() {
        composeRule.setContent {
            LocalModelTestScreen(
                state = LocalModelUiState(
                    selectedFilename = "stored.gguf",
                    modelSizeBytes = 2048,
                    hasStoredCopy = true,
                    status = "Stored model available"
                ),
                prompt = "",
                onPromptChange = {},
                onSelectModel = {},
                onLoadSavedModel = {},
                onRun = {},
                onGrammarSmoke = {},
                onUnload = {}
            )
        }

        composeRule.onNodeWithText("GGUF Modeli Seç").assertIsEnabled()
        composeRule.onNodeWithText("Kayıtlı Kopyayı Yükle").assertIsEnabled()
        composeRule.onNodeWithText("Çalıştır").assertIsNotEnabled()
    }
    @Test
    fun thinking_isOneToggleAndPreservesRuntimeOptionCallback() {
        var requestedThinking: Boolean? = null
        val thinking = mutableStateOf(false)
        var requestedTokens = 0
        composeRule.setContent {
            LocalModelTestScreen(
                state = LocalModelUiState(
                    phase = LocalModelPhase.READY,
                    selectedFilename = "qwen2-1_5b-instruct-q4_k_m.gguf",
                    modelSizeBytes = 986_080_256,
                    hasActiveModel = true,
                    status = "Hazır",
                    enableThinking = thinking.value
                ),
                prompt = "test",
                onPromptChange = {},
                onSelectModel = {},
                onLoadSavedModel = {},
                onRun = {},
                onThinkingChange = { requestedThinking = it; thinking.value = it },
                onMaxTokensChange = { requestedTokens = it },
                onGrammarSmoke = {},
                onUnload = {}
            )
        }

        composeRule.onAllNodesWithText("Thinking: OFF").assertCountEquals(1)
        composeRule.onNodeWithText("Thinking: OFF").performClick()
        assertEquals(true, requestedThinking)
        composeRule.onAllNodesWithText("Thinking:", substring = true).assertCountEquals(1)
        composeRule.onNodeWithText("Thinking: ON").performClick()
        assertEquals(false, requestedThinking)
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))[0].performSemanticsAction(SemanticsActions.SetProgress) { it(16f) }
        assertEquals(16, requestedTokens)
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))[0].performSemanticsAction(SemanticsActions.SetProgress) { it(512f) }
        assertEquals(512, requestedTokens)
    }
}
