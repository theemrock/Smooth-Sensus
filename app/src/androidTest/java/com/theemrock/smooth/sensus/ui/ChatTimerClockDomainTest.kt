package com.theemrock.smooth.sensus.ui

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.theemrock.smooth.sensus.conversation.ChatConversation
import com.theemrock.smooth.sensus.conversation.ChatInferenceGateway
import com.theemrock.smooth.sensus.conversation.ChatMessage
import com.theemrock.smooth.sensus.conversation.ChatRepository
import com.theemrock.smooth.sensus.conversation.MessageInputMode
import com.theemrock.smooth.sensus.conversation.MessageRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatTimerClockDomainTest {
    @Test
    fun generationStartUsesElapsedRealtimeClockDomain() {
        val inferenceResult = CompletableDeferred<String>()
        val viewModel = ChatViewModel(
            repository = ClockDomainRepository(),
            inference = ChatInferenceGateway { _, _, _ -> inferenceResult.await() },
            operationScope = CoroutineScope(Dispatchers.Unconfined),
        )

        val beforeSubmit = SystemClock.elapsedRealtime()
        viewModel.send("merhaba")
        val afterSubmit = SystemClock.elapsedRealtime()

        val startedAt = viewModel.uiState.value.generationStartedAtElapsedMs
        assertNotNull(startedAt)
        assertTrue(
            "generation start $startedAt must share elapsedRealtime domain $beforeSubmit..$afterSubmit",
            requireNotNull(startedAt) in beforeSubmit..afterSubmit,
        )
        inferenceResult.complete("yanit")
    }

    private class ClockDomainRepository : ChatRepository {
        private val conversation = ChatConversation("first", "Yeni sohbet", 1L, 1L)

        override fun observeConversations(): Flow<List<ChatConversation>> =
            MutableStateFlow(listOf(conversation))

        override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
            MutableStateFlow(emptyList())

        override suspend fun createConversation(): String = conversation.id
        override suspend fun deleteConversation(conversationId: String) = Unit

        override suspend fun addMessage(
            conversationId: String,
            role: MessageRole,
            content: String,
            inputMode: MessageInputMode,
            replyToMessageId: String?,
        ): ChatMessage = ChatMessage(
            id = "$role-$content",
            conversationId = conversationId,
            role = role,
            content = content,
            timestamp = 1L,
            inputMode = inputMode,
            replyToMessageId = replyToMessageId,
        )

        override suspend fun editMessage(messageId: String, content: String) = Unit

        override suspend fun editMessageAndDeleteAfter(
            conversationId: String,
            messageId: String,
            content: String,
        ): ChatMessage = error("not used")

        override suspend fun deleteMessage(messageId: String) = Unit
        override suspend fun forkConversation(conversationId: String, throughMessageId: String): String = error("not used")
        override suspend fun recentMessages(conversationId: String, limit: Int): List<ChatMessage> = emptyList()
    }
}
