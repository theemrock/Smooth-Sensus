package com.theemrock.smooth.sensus.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SmoothHomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsSmoothIdentityAndOpensRealSections() {
        var learnOpenCount = 0
        var chatOpenCount = 0
        var aboutOpenCount = 0
        composeRule.setContent {
            SmoothHomeScreen(
                onOpenLearnScenario = { learnOpenCount++ },
                onOpenChat = { chatOpenCount++ },
                onOpenAbout = { aboutOpenCount++ }
            )
        }

        composeRule.onNodeWithText("Smooth Dialogue").assertIsDisplayed()
        composeRule.onNodeWithText("Learn & Scenario").performClick()
        composeRule.onNodeWithText("Chat").performClick()
        composeRule.onNodeWithText("Hakkında").performClick()

        assertEquals(1, learnOpenCount)
        assertEquals(1, chatOpenCount)
        assertEquals(1, aboutOpenCount)
    }

    @Test
    fun aboutShowsProductionIdentity() {
        composeRule.setContent { AboutScreen(onBack = {}) }

        composeRule.onNodeWithText("Smooth Dialogue").assertIsDisplayed()
        composeRule.onNodeWithText("Sürüm 0.0.6 (6)").assertIsDisplayed()
        composeRule.onNodeWithText("com.theemrock.smooth.sensus").assertIsDisplayed()
        composeRule.onNodeWithText("Yerel çıkarım llama.cpp ile sağlanır.").assertIsDisplayed()
    }
}
