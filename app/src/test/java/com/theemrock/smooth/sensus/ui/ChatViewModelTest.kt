package com.theemrock.smooth.sensus.ui

import com.theemrock.smooth.sensus.conversation.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ChatViewModelTest {
    @Test fun `response duration starts at submit and freezes on assistant completion`() {
        val repo = FakeChatRepository()
        val result = CompletableDeferred<String>()
        var elapsedMs = 1_000L
        val vm = ChatViewModel(
            repo,
            ChatInferenceGateway { _, _, _ -> result.await() },
            CoroutineScope(Dispatchers.Unconfined),
            elapsedRealtimeMs = { elapsedMs }
        )

        vm.send("merhaba")

        assertTrue(vm.uiState.value.isGenerating)
        assertEquals("first", vm.uiState.value.generatingConversationId)
        assertEquals(1_000L, vm.uiState.value.generationStartedAtElapsedMs)
        elapsedMs = 3_940L
        val liveElapsedImmediatelyBeforeCompletion = elapsedMs - 1_000L
        result.complete("yanit")

        val assistant = repo.messages.getValue("first").last()
        assertFalse(vm.uiState.value.isGenerating)
        assertNull(vm.uiState.value.generationStartedAtElapsedMs)
        assertEquals(2_940L, vm.uiState.value.responseDurationsMs[assistant.id])
        assertEquals(liveElapsedImmediatelyBeforeCompletion, vm.uiState.value.responseDurationsMs[assistant.id])
    }

    @Test fun `reply cancellation clears only pending reply`() {
        val repo = FakeChatRepository()
        val vm = testViewModel(repo)
        val target = repo.seed("first", "hedef")
        repo.seed("first", "sonraki")
        vm.selectConversation("first")
        vm.startReply(target.id)

        vm.cancelReply()

        assertNull(vm.uiState.value.replyTarget)
        assertEquals(listOf("hedef", "sonraki"), repo.messages.getValue("first").map { it.content })
    }

    @Test fun `multiple conversations switch and keep isolated messages`() {
        val repo = FakeChatRepository(); val vm = testViewModel(repo)
        val second = vm.newConversation(); vm.send("ikinci"); vm.selectConversation("first"); vm.send("birinci")
        assertEquals(listOf("birinci", "yanit"), repo.messages.getValue("first").map { it.content })
        assertEquals(listOf("ikinci", "yanit"), repo.messages.getValue(second).map { it.content })
    }

    @Test fun `generation state and result stay with originating conversation across switches`() {
        val repo = FakeChatRepository().apply { ensureConversation("second") }
        val result = CompletableDeferred<String>()
        val vm = ChatViewModel(
            repo,
            ChatInferenceGateway { _, _, _ -> result.await() },
            CoroutineScope(Dispatchers.Unconfined),
            elapsedRealtimeMs = { 1_000L },
        )

        vm.send("A request")
        vm.selectConversation("second")

        assertFalse(vm.uiState.value.isGenerating)
        assertTrue(vm.uiState.value.isRuntimeBusy)
        assertNull(vm.uiState.value.generationStartedAtElapsedMs)

        vm.selectConversation("first")
        assertTrue(vm.uiState.value.isGenerating)
        assertEquals(1_000L, vm.uiState.value.generationStartedAtElapsedMs)

        vm.selectConversation("second")
        result.complete("A response")

        assertEquals(emptyList<ChatMessage>(), repo.messages.getValue("second"))
        assertEquals(listOf("A request", "A response"), repo.messages.getValue("first").map { it.content })
        assertFalse(vm.uiState.value.isGenerating)
        assertFalse(vm.uiState.value.isRuntimeBusy)

        vm.selectConversation("first")
        assertFalse(vm.uiState.value.isGenerating)
        assertNull(vm.uiState.value.generationStartedAtElapsedMs)
    }

    @Test fun `draft and reply state are restored per conversation`() {
        val repo = FakeChatRepository().apply { ensureConversation("second") }
        val vm = testViewModel(repo)
        val targetA = repo.seed("first", "A target")
        val targetB = repo.seed("second", "B target")

        vm.selectConversation("first")
        vm.updateDraft("A draft")
        vm.startReply(targetA.id)
        vm.selectConversation("second")

        assertEquals("", vm.uiState.value.draft)
        assertNull(vm.uiState.value.replyTarget)
        vm.updateDraft("B draft")
        vm.startReply(targetB.id)

        vm.selectConversation("first")
        assertEquals("A draft", vm.uiState.value.draft)
        assertEquals(targetA.id, vm.uiState.value.replyTarget?.id)

        vm.selectConversation("second")
        assertEquals("B draft", vm.uiState.value.draft)
        assertEquals(targetB.id, vm.uiState.value.replyTarget?.id)
    }

    @Test fun `generation failure belongs only to originating conversation`() {
        val repo = FakeChatRepository().apply { ensureConversation("second") }
        val result = CompletableDeferred<String>()
        val vm = ChatViewModel(
            repo,
            ChatInferenceGateway { _, _, _ -> result.await() },
            CoroutineScope(Dispatchers.Unconfined),
            elapsedRealtimeMs = { 0L },
        )

        vm.send("A request")
        vm.selectConversation("second")
        result.completeExceptionally(IllegalStateException("A failed"))

        assertNull(vm.uiState.value.errorMessage)
        vm.selectConversation("first")
        assertEquals("A failed", vm.uiState.value.errorMessage)
    }

    @Test fun `multiple conversations run FIFO on one inference and route results to origin`() {
        val repo = FakeChatRepository().apply { ensureConversation("second") }
        val firstResult = CompletableDeferred<String>()
        val secondResult = CompletableDeferred<String>()
        var calls = 0
        var active = 0
        var maxActive = 0
        val vm = ChatViewModel(
            repo,
            ChatInferenceGateway { _, _, _ ->
                val call = calls++
                active++
                maxActive = maxOf(maxActive, active)
                try { if (call == 0) firstResult.await() else secondResult.await() }
                finally { active-- }
            },
            CoroutineScope(Dispatchers.Unconfined),
            elapsedRealtimeMs = { 1_000L },
        )

        vm.send("A request")
        vm.selectConversation("second")
        vm.send("B request")

        assertTrue(vm.uiState.value.isQueued)
        assertFalse(vm.uiState.value.isGenerating)
        firstResult.complete("A response")
        assertTrue(vm.uiState.value.isGenerating)
        assertFalse(vm.uiState.value.isQueued)
        assertEquals(listOf("A request", "A response"), repo.messages.getValue("first").map { it.content })
        assertEquals(listOf("B request"), repo.messages.getValue("second").map { it.content })
        assertTrue("A completion while viewing B is unread", "first" in vm.uiState.value.unreadConversationIds)

        secondResult.complete("B response")

        assertEquals(1, maxActive)
        assertEquals(listOf("B request", "B response"), repo.messages.getValue("second").map { it.content })
        vm.selectConversation("first")
        assertFalse("opening A clears unread", "first" in vm.uiState.value.unreadConversationIds)
    }

    @Test fun `failed queued job does not block the next conversation`() {
        val repo = FakeChatRepository().apply { ensureConversation("second") }
        val firstResult = CompletableDeferred<String>()
        val secondResult = CompletableDeferred<String>()
        var calls = 0
        val vm = ChatViewModel(
            repo,
            ChatInferenceGateway { _, _, _ -> if (calls++ == 0) firstResult.await() else secondResult.await() },
            CoroutineScope(Dispatchers.Unconfined),
            elapsedRealtimeMs = { 0L },
        )

        vm.send("A request")
        vm.selectConversation("second")
        vm.send("B request")
        firstResult.completeExceptionally(IllegalStateException("A failed"))

        assertTrue(vm.uiState.value.isGenerating)
        secondResult.complete("B response")
        assertEquals(listOf("B request", "B response"), repo.messages.getValue("second").map { it.content })
        vm.selectConversation("first")
        assertEquals("A failed", vm.uiState.value.errorMessage)
    }

    @Test fun `generation consumes configured history output and thinking settings`() {
        val repo = FakeChatRepository()
        repeat(6) { repo.seed("first", "old-$it") }
        var receivedCount = 0
        var receivedMax = 0
        var receivedThinking = false
        val vm = ChatViewModel(
            repo,
            ChatInferenceGateway { messages, maxTokens, enableThinking ->
                receivedCount = messages.size; receivedMax = maxTokens; receivedThinking = enableThinking; "response"
            },
            CoroutineScope(Dispatchers.Unconfined),
            elapsedRealtimeMs = { 0L },
            settingsProvider = { ChatRuntimeSettings(historyWindow = 4, maxOutputTokens = 32, enableThinking = true) },
        )

        vm.send("new")

        assertEquals(4, receivedCount)
        assertEquals(4, repo.requestedHistoryLimit)
        assertEquals(32, receivedMax)
        assertTrue(receivedThinking)
    }

    @Test fun `failed inference persists user but no assistant`() {
        val repo = FakeChatRepository(); val vm = testViewModel(repo, FakeInference(IllegalStateException("model yok")))
        vm.send("merhaba")
        assertEquals(listOf(MessageRole.USER), repo.messages.getValue("first").map { it.role })
        assertEquals("model yok", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isGenerating)
        assertNull(vm.uiState.value.generationStartedAtElapsedMs)
    }

    @Test fun `empty message cannot be sent`() {
        val repo = FakeChatRepository(); testViewModel(repo).send("   ")
        assertEquals(emptyList<ChatMessage>(), repo.messages.getValue("first"))
    }

    @Test fun `edit delete fork and reply preserve their distinct semantics`() {
        val repo = FakeChatRepository(); val vm = testViewModel(repo)
        val a = repo.seed("first", "A"); val b = repo.seed("first", "B"); val c = repo.seed("first", "C"); repo.seed("first", "D")
        vm.edit(c.id, "C2"); vm.delete(b.id)
        assertEquals(listOf("A", "C2", "D"), repo.messages.getValue("first").map { it.content })
        val forkId = vm.fork(c.id)
        assertEquals(listOf("A", "C2"), repo.messages.getValue(forkId).map { it.content })
        assertEquals(listOf("A", "C2", "D"), repo.messages.getValue("first").map { it.content })
        vm.selectConversation("first"); vm.startReply(a.id); vm.send("reply")
        assertEquals(a.id, repo.messages.getValue("first").first { it.content == "reply" }.replyToMessageId)
        assertNull(vm.uiState.value.replyTarget)
    }

    @Test fun `conversation deletion selects another conversation`() {
        val repo = FakeChatRepository(); val vm = testViewModel(repo)
        val second = vm.newConversation(); vm.deleteConversation(second)
        assertEquals("first", vm.uiState.value.selectedConversationId)
        assertFalse(repo.conversations.value.any { it.id == second })
    }

    @Test fun `edit save changes only selected message and preserves downstream`() {
        val repo = FakeChatRepository(); val vm = testViewModel(repo)
        val first = repo.seed("first", "yanlis"); repo.seed("first", "old answer", MessageRole.ASSISTANT); repo.seed("first", "later")

        vm.edit(first.id, "duzeltilmis")

        assertEquals(listOf("duzeltilmis", "old answer", "later"), repo.messages.getValue("first").map { it.content })
    }

    @Test fun `edit and send truncates same conversation and persists replacement assistant`() {
        val repo = FakeChatRepository(); val vm = testViewModel(repo)
        val a1 = repo.seed("first", "A1"); val a2 = repo.seed("first", "A2"); repo.seed("first", "A3", MessageRole.ASSISTANT)
        repo.ensureConversation("second"); repo.seed("second", "B1"); repo.seed("second", "B2", MessageRole.ASSISTANT)

        vm.editAndSend(a2.id, "A2-edited")

        assertEquals(listOf("A1", "A2-edited", "yanit"), repo.messages.getValue("first").map { it.content })
        assertEquals("first", repo.messages.getValue("first").last().conversationId)
        assertEquals(listOf("B1", "B2"), repo.messages.getValue("second").map { it.content })
        assertEquals(a1.id, repo.messages.getValue("first").first().id)
    }

    @Test fun `failed edit and send leaves edited user as tip without fake assistant`() {
        val repo = FakeChatRepository(); val vm = testViewModel(repo, FakeInference(IllegalStateException("model yok")))
        val selected = repo.seed("first", "old"); repo.seed("first", "downstream", MessageRole.ASSISTANT)

        vm.editAndSend(selected.id, "edited")

        assertEquals(listOf("edited"), repo.messages.getValue("first").map { it.content })
        assertEquals("model yok", vm.uiState.value.errorMessage)
    }

    @Test fun `empty edit and send is ignored`() {
        val repo = FakeChatRepository(); val vm = testViewModel(repo)
        val selected = repo.seed("first", "original"); repo.seed("first", "later")
        vm.editAndSend(selected.id, "   ")
        assertEquals(listOf("original", "later"), repo.messages.getValue("first").map { it.content })
    }

    @Test fun `cancellation stops active response timer cleanly`() {
        val repo = FakeChatRepository()
        val result = CompletableDeferred<String>()
        val operationJob = Job()
        val vm = ChatViewModel(
            repo,
            ChatInferenceGateway { _, _, _ -> result.await() },
            CoroutineScope(Dispatchers.Unconfined + operationJob),
            elapsedRealtimeMs = { 42_000L }
        )
        vm.send("bekle")
        assertTrue(vm.uiState.value.isGenerating)
        assertEquals(42_000L, vm.uiState.value.generationStartedAtElapsedMs)

        operationJob.cancel()

        assertFalse(vm.uiState.value.isGenerating)
        assertNull(vm.uiState.value.generationStartedAtElapsedMs)
    }

    private fun testViewModel(
        repository: FakeChatRepository,
        inference: ChatInferenceGateway = FakeInference(),
    ) = ChatViewModel(
        repository,
        inference,
        CoroutineScope(Dispatchers.Unconfined),
        elapsedRealtimeMs = { 0L },
    )

    private class FakeInference(private val failure: Throwable? = null) : ChatInferenceGateway {
        override suspend fun generate(messages: List<ChatMessage>, maxTokens: Int, enableThinking: Boolean): String { failure?.let { throw it }; return "yanit" }
    }

    private class FakeChatRepository : ChatRepository {
        val conversations = MutableStateFlow(listOf(ChatConversation("first", "Yeni sohbet", 1, 1)))
        val messages = linkedMapOf("first" to mutableListOf<ChatMessage>()); private var next = 1
        var requestedHistoryLimit: Int? = null
        override fun observeConversations(): Flow<List<ChatConversation>> = conversations
        override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = MutableStateFlow(messages.getOrPut(conversationId) { mutableListOf() }.toList())
        override suspend fun createConversation(): String = "conversation-${next++}".also { id -> messages[id] = mutableListOf(); conversations.value = listOf(ChatConversation(id, "Yeni sohbet", next.toLong(), next.toLong())) + conversations.value }
        override suspend fun deleteConversation(conversationId: String) { messages.remove(conversationId); conversations.value = conversations.value.filterNot { it.id == conversationId } }
        override suspend fun addMessage(conversationId: String, role: MessageRole, content: String, inputMode: MessageInputMode, replyToMessageId: String?): ChatMessage = seed(conversationId, content, role, replyToMessageId)
        override suspend fun editMessage(messageId: String, content: String) { messages.values.flatten().first { it.id == messageId }.let { old -> messages.getValue(old.conversationId)[messages.getValue(old.conversationId).indexOf(old)] = old.copy(content = content) } }
        override suspend fun editMessageAndDeleteAfter(conversationId: String, messageId: String, content: String): ChatMessage {
            val own = messages.getValue(conversationId); val index = own.indexOfFirst { it.id == messageId }; require(index >= 0)
            own[index] = own[index].copy(content = content); while (own.size > index + 1) own.removeLast(); return own[index]
        }
        override suspend fun deleteMessage(messageId: String) { messages.values.forEach { it.removeAll { message -> message.id == messageId } } }
        override suspend fun forkConversation(conversationId: String, throughMessageId: String): String { val id = createConversation(); for (message in messages.getValue(conversationId)) { messages.getValue(id) += message.copy(id = "m${next++}", conversationId = id); if (message.id == throughMessageId) break }; return id }
        override suspend fun recentMessages(conversationId: String, limit: Int): List<ChatMessage> {
            requestedHistoryLimit = limit
            return messages.getValue(conversationId).takeLast(limit)
        }
        fun seed(conversationId: String, content: String, role: MessageRole = MessageRole.USER, reply: String? = null): ChatMessage = ChatMessage("m${next++}", conversationId, role, content, next.toLong(), MessageInputMode.TEXT, reply).also { messages.getValue(conversationId) += it }
        fun ensureConversation(id: String) { messages[id] = mutableListOf(); conversations.value = conversations.value + ChatConversation(id, "Yeni sohbet", next.toLong(), next.toLong()) }
    }
}
