package com.theemrock.smooth.sensus.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.theemrock.smooth.sensus.conversation.ChatConversation
import com.theemrock.smooth.sensus.conversation.ChatMessage
import com.theemrock.smooth.sensus.conversation.MessageInputMode
import com.theemrock.smooth.sensus.conversation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ChatPresentationTest {
    @get:Rule val rule = createComposeRule()
    @Volatile private var imeVisible = false

    private fun messages(conversation: String, count: Int) = (1..count).map {
        ChatMessage("$conversation-$it", conversation, MessageRole.USER,
            "$conversation message $it\nA second line of content\nA third line of content", it.toLong(), MessageInputMode.TEXT, null)
    }

    private fun show(
        state: MutableState<ChatUiState>,
        send: (String) -> Unit = {},
        openModel: () -> Unit = {},
        reply: (String) -> Unit = {},
        cancelReply: () -> Unit = {},
    ) {
        rule.setContent {
            val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            SideEffect { imeVisible = keyboardVisible }
            ChatScreen(
                state.value, send, {}, { _, _ -> }, { _, _ -> }, {}, reply, cancelReply,
                {}, {}, {}, {}, {}, openModel,
                onDraftChange = { state.value = state.value.copy(draft = it) },
            )
        }
    }

    @Test fun sendClearsFocusAndDraftButNewlineDoesNot() {
        var sent = ""
        val state = mutableStateOf(ChatUiState())
        show(
            state,
            send = { sent = it; state.value = state.value.copy(draft = "") },
        )
        rule.onNode(hasSetTextAction()).performClick().performTextInput("Hello\nworld")
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.waitUntil(5_000) { imeVisible }
        rule.onNodeWithContentDescription("Gönder").performClick()
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        rule.waitUntil(5_000) { !imeVisible }
        rule.runOnIdle {
            assertEquals("Hello\nworld", sent)
            state.value = state.value.copy(isGenerating = true)
        }
        rule.runOnIdle { state.value = state.value.copy(messages = messages("A", 1).map { it.copy(role = MessageRole.ASSISTANT) }, isGenerating = false) }
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
        rule.runOnIdle { org.junit.Assert.assertFalse(imeVisible) }
    }

    @Test fun openingAndSwitchingConversationsShowsTipIncludingAsyncLoad() {
        val state = mutableStateOf(ChatUiState(selectedConversationId = "A", messages = messages("A", 30)))
        show(state)
        rule.onNodeWithText("A message 30\nA second line of content\nA third line of content").assertIsDisplayed()
        rule.runOnIdle { state.value = ChatUiState(selectedConversationId = "B") }
        rule.runOnIdle { state.value = state.value.copy(messages = messages("B", 35)) }
        rule.onNodeWithText("B message 35\nA second line of content\nA third line of content").assertIsDisplayed()
    }

    @Test fun arrivalFollowsBottomButPreservesReadingPositionAndSendReturnsToTip() {
        val state = mutableStateOf(ChatUiState(selectedConversationId = "A", messages = messages("A", 30)))
        show(state, send = { state.value = state.value.copy(messages = messages("A", 33)) })
        rule.runOnIdle { state.value = state.value.copy(messages = messages("A", 31).map { if (it.id == "A-31") it.copy(role = MessageRole.ASSISTANT) else it }) }
        rule.onNodeWithText("A message 31\nA second line of content\nA third line of content").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("A message", substring = true))).performScrollToIndex(0)
        rule.onNodeWithText("A message 1\nA second line of content\nA third line of content").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(messages = messages("A", 32)) }
        rule.onNodeWithText("A message 1\nA second line of content\nA third line of content").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("Send while reading")
        rule.onNodeWithContentDescription("Gönder").performClick()
        rule.onNodeWithText("A message 33\nA second line of content\nA third line of content").assertIsDisplayed()
    }

    @Test fun drawerSectionsCollapseIndependentlyAndPlaceholdersAreDisabled() {
        val state = mutableStateOf(ChatUiState(conversations = listOf(ChatConversation("A", "Existing chat", 1, 1))))
        var modelOpenCount = 0
        show(state, openModel = { modelOpenCount++ })
        rule.onNodeWithContentDescription("Sohbet menüsü").performClick()
        rule.onNodeWithText("Existing chat").assertIsDisplayed()
        rule.onNodeWithText("Gizli Sohbetler").assertIsNotEnabled()
        rule.onNodeWithText("PROJELER").performClick()
        rule.onNodeWithText("Yeni proje").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("TALİMATLAR").performClick()
        rule.onNodeWithText("Genel").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("SOHBETLER").performClick()
        rule.onNodeWithText("Existing chat").assertDoesNotExist()
        rule.onNodeWithText("Yeni proje").assertIsDisplayed()
        rule.onNodeWithText("ARAÇLAR").performClick()
        rule.onNodeWithText("Yerel Model").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, modelOpenCount) }
        rule.onNodeWithContentDescription("Sohbet menüsü").performClick()
        rule.onNodeWithText("ARAÇLAR").performClick()
        rule.onNodeWithText("Yerel Model").assertDoesNotExist()
        rule.onNodeWithText("Hakkında").assertDoesNotExist()
    }

    @Test fun messageMetadataReplyQuoteMarkdownAndLiveTimerAreVisible() {
        val zone = ZoneId.of("Europe/Istanbul")
        val timestamp = ZonedDateTime.of(2026, 10, 1, 8, 42, 0, 0, zone).toInstant().toEpochMilli()
        val target = ChatMessage("target", "A", MessageRole.USER, "Hatırlanacak uzun hedef metin", timestamp, MessageInputMode.TEXT, null)
        val reply = ChatMessage(
            "reply", "A", MessageRole.ASSISTANT,
            "# Başlık\n\n**Kalın** ve *italik* ve `kod`\n\n- Madde\n1. Birinci\n```\nval x = 1\n```",
            timestamp + 1_000, MessageInputMode.TEXT, target.id
        )
        rule.setContent {
            ChatScreen(
                state = ChatUiState(
                    selectedConversationId = "A",
                    messages = listOf(target, reply),
                    isGenerating = true,
                    generatingConversationId = "A",
                    generationStartedAtElapsedMs = 1_000,
                    responseDurationsMs = mapOf(reply.id to 2_940)
                ),
                onSend = {}, onDelete = {}, onEdit = { _, _ -> }, onEditAndSend = { _, _ -> },
                onFork = {}, onReply = {}, onCancelReply = {}, onNewConversation = {},
                onSelectConversation = {}, onDeleteConversation = {}, onClearError = {}, onBack = {},
                zoneId = zone, locale = Locale.forLanguageTag("tr-TR"), elapsedRealtimeMs = { 2_400 }
            )
        }

        rule.onNodeWithText("1 Ekim 2026 Perşembe").assertIsDisplayed()
        rule.onAllNodesWithText("08:42").assertCountEquals(1)
        rule.onNodeWithText("08:42 · 2,9 sn").assertIsDisplayed()
        rule.onNodeWithText("Yanıtlanan: Sen").assertIsDisplayed()
        rule.onAllNodesWithText("Hatırlanacak uzun hedef metin").assertCountEquals(2)
        rule.onNodeWithText("Başlık").assertIsDisplayed()
        rule.onNodeWithText("Kalın ve italik ve kod").assertIsDisplayed()
        rule.onNodeWithText("• Madde").assertIsDisplayed()
        rule.onNodeWithText("1. Birinci").assertIsDisplayed()
        rule.onNodeWithText("val x = 1").assertIsDisplayed()
        rule.onNodeWithText("1,4 sn").assertIsDisplayed()
        rule.onNodeWithText("Yanıt hazırlanıyor…").assertDoesNotExist()
        rule.onNodeWithContentDescription("Gönder").assertIsNotEnabled()
    }

    @Test fun liveTimerStartsNearZeroAndSurvivesRecompositionAndConversationSwitching() {
        rule.mainClock.autoAdvance = false
        var currentElapsedRealtimeMs = 824_244_000L
        val elapsedRealtime = { currentElapsedRealtimeMs }
        val active = ChatUiState(
            selectedConversationId = "A",
            isGenerating = true,
            generatingConversationId = "A",
            generationStartedAtElapsedMs = 824_244_000L,
        )
        val state = mutableStateOf(active)
        rule.setContent {
            ChatScreen(
                state = state.value,
                onSend = {}, onDelete = {}, onEdit = { _, _ -> }, onEditAndSend = { _, _ -> },
                onFork = {}, onReply = {}, onCancelReply = {}, onNewConversation = {},
                onSelectConversation = {}, onDeleteConversation = {}, onClearError = {}, onBack = {},
                locale = Locale.forLanguageTag("tr-TR"),
                elapsedRealtimeMs = elapsedRealtime,
            )
        }

        rule.onNodeWithText("0,0 sn").assertIsDisplayed()
        rule.runOnIdle { currentElapsedRealtimeMs += 100L }
        rule.mainClock.advanceTimeBy(250L)
        rule.onNodeWithText("0,1 sn").assertIsDisplayed()

        rule.runOnIdle { state.value = active.copy(errorMessage = "recompose") }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("0,1 sn").assertIsDisplayed()
        rule.runOnIdle { state.value = active.copy(selectedConversationId = "B") }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("0,1 sn").assertDoesNotExist()
        rule.runOnIdle {
            currentElapsedRealtimeMs += 100L
            state.value = active
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("0,2 sn").assertIsDisplayed()
        rule.onNodeWithText("824244,2 sn").assertDoesNotExist()
    }

    @Test fun pendingReplyPreviewCanBeCancelledWithoutChangingMessages() {
        val target = ChatMessage("target", "A", MessageRole.ASSISTANT, "Uzun hedef içerik", 1, MessageInputMode.TEXT, null)
        val state = mutableStateOf(ChatUiState(selectedConversationId = "A", messages = listOf(target)))
        show(
            state,
            reply = { id -> state.value = state.value.copy(replyTarget = state.value.messages.first { it.id == id }) },
            cancelReply = { state.value = state.value.copy(replyTarget = null) }
        )

        rule.onNodeWithText("Uzun hedef içerik").performTouchInput { longClick() }
        rule.onNodeWithText("Yanıtla").performClick()

        rule.onNodeWithText("Yanıt: Asistan").assertIsDisplayed()
        rule.onAllNodesWithText("Uzun hedef içerik").assertCountEquals(2)
        rule.onNodeWithContentDescription("Yanıtı iptal et").performClick()
        rule.runOnIdle {
            assertEquals(listOf(target), state.value.messages)
            org.junit.Assert.assertNull(state.value.replyTarget)
        }
    }

    @Test fun switchingBackRestoresConversationScrollPosition() {
        val state = mutableStateOf(ChatUiState(selectedConversationId = "A", messages = messages("A", 30)))
        show(state)
        val list = rule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("A message", substring = true)))
        list.performScrollToIndex(8)
        rule.onNodeWithText("A message 8\nA second line of content\nA third line of content").assertIsDisplayed()

        rule.runOnIdle { state.value = ChatUiState(selectedConversationId = "B", messages = messages("B", 30)) }
        rule.onNodeWithText("B message 30\nA second line of content\nA third line of content").assertIsDisplayed()
        rule.runOnIdle { state.value = ChatUiState(selectedConversationId = "A", messages = messages("A", 30)) }

        rule.onNodeWithText("A message 8\nA second line of content\nA third line of content").assertIsDisplayed()
    }

    @Test fun longAssistantTailCanBeExposedAboveComposer() {
        val content = (1..45).joinToString("\n\n") { "Uzun yanıt satırı $it" } + "\n\nTAIL_MARKER"
        val assistant = ChatMessage("long", "A", MessageRole.ASSISTANT, content, 1, MessageInputMode.TEXT, null)
        val state = mutableStateOf(ChatUiState(selectedConversationId = "A", messages = listOf(assistant)))
        show(state)

        rule.onNodeWithText("TAIL_MARKER").performScrollTo().assertIsDisplayed()
        rule.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun fastUpwardHistoryFlingDismissesOpenIme() {
        val state = mutableStateOf(ChatUiState(selectedConversationId = "A", messages = messages("A", 35)))
        show(state)
        rule.onNode(hasSetTextAction()).performClick().performTextInput("taslak")
        rule.waitUntil(5_000) { imeVisible }

        rule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("A message", substring = true)))
            .performTouchInput { swipeUp(durationMillis = 100) }

        rule.waitUntil(5_000) { !imeVisible }
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
    }
}
