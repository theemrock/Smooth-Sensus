package com.theemrock.smooth.sensus.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.theemrock.smooth.sensus.conversation.ChatMessage
import com.theemrock.smooth.sensus.conversation.MessageInputMode
import com.theemrock.smooth.sensus.conversation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatComposerTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun composerUsesCompactSendIconAndSubmitsText() {
        var sent = ""
        val state = mutableStateOf(ChatUiState())
        composeRule.setContent {
            ChatScreen(
                state = state.value, onSend = { sent = it; state.value = state.value.copy(draft = "") }, onDelete = {}, onEdit = { _, _ -> }, onEditAndSend = { _, _ -> },
                onFork = {}, onReply = {}, onCancelReply = {}, onNewConversation = {},
                onSelectConversation = {}, onDeleteConversation = {}, onClearError = {}, onBack = {},
                onDraftChange = { state.value = state.value.copy(draft = it) },
            )
        }

        composeRule.onNodeWithText("Mesaj").performTextInput("Merhaba")
        composeRule.onNodeWithContentDescription("Gönder").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithText("Gönder").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals("Merhaba", sent) }
    }

    @Test fun longUserMessageOpensFullEditSurfaceWithTwoActions() {
        val content = "Uzun kullanıcı mesajı\n\nİkinci paragraf ve düzenleme içeriği."
        composeRule.setContent {
            ChatScreen(
                state = ChatUiState(messages = listOf(ChatMessage("m1", "c1", MessageRole.USER, content, 1, MessageInputMode.TEXT, null))),
                onSend = {}, onDelete = {}, onEdit = { _, _ -> }, onEditAndSend = { _, _ -> }, onFork = {},
                onReply = {}, onCancelReply = {}, onNewConversation = {}, onSelectConversation = {},
                onDeleteConversation = {}, onClearError = {}, onBack = {}
            )
        }
        composeRule.onNodeWithText(content).performTouchInput { longClick() }
        composeRule.onNodeWithText("Düzenle").performClick()
        composeRule.onNodeWithText("Mesajı düzenle").assertIsDisplayed()
        composeRule.onNodeWithText("Kaydet").assertIsDisplayed()
        composeRule.onNodeWithText("Düzenle ve Gönder").assertIsDisplayed()
        composeRule.onNodeWithText("İptal").assertIsDisplayed()
    }
}
