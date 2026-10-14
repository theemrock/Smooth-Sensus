package com.theemrock.smooth.sensus.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theemrock.smooth.sensus.SmoothApplication
import com.theemrock.smooth.sensus.conversation.*
import com.theemrock.smooth.sensus.data.AppDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ChatGenerationLifecycle { IDLE, QUEUED, PROCESSING, COMPLETED, FAILED }

data class ChatUiState(
    val conversations: List<ChatConversation> = emptyList(), val selectedConversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(), val draft: String = "", val replyTarget: ChatMessage? = null,
    val generationLifecycle: ChatGenerationLifecycle = ChatGenerationLifecycle.IDLE,
    val isGenerating: Boolean = false, val isQueued: Boolean = false, val isRuntimeBusy: Boolean = false,
    val generatingConversationId: String? = null, val generationStartedAtElapsedMs: Long? = null,
    val responseDurationsMs: Map<String, Long> = emptyMap(), val unreadConversationIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
)

private data class ConversationTransientState(
    val draft: String = "", val replyTarget: ChatMessage? = null,
    val lifecycle: ChatGenerationLifecycle = ChatGenerationLifecycle.IDLE,
    val generationStartedAtElapsedMs: Long? = null, val errorMessage: String? = null,
)
private data class GenerationRequest(val conversationId: String, val settings: ChatRuntimeSettings, val prepared: CompletableDeferred<Unit>)
fun interface ChatCompletionNotifier { fun responseReady(conversationId: String) }

class ChatViewModel internal constructor(
    private val repository: ChatRepository, private val inference: ChatInferenceGateway,
    operationScope: CoroutineScope? = null, private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val settingsProvider: () -> ChatRuntimeSettings = { ChatRuntimeSettings() },
    private val completionNotifier: ChatCompletionNotifier = ChatCompletionNotifier {},
) : ViewModel() {
    private val scope = operationScope ?: viewModelScope
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState
    private var messagesJob: Job? = null
    private val transientStates = mutableMapOf<String, ConversationTransientState>()
    private val generationQueue = ArrayDeque<GenerationRequest>()
    private var processorJob: Job? = null

    init { scope.launch { repository.observeConversations().collect { conversations ->
        _uiState.value = _uiState.value.copy(conversations = conversations)
        if (_uiState.value.selectedConversationId == null) selectConversation(if (conversations.isEmpty()) repository.createConversation() else conversations.first().id)
    } } }

    fun selectConversation(id: String) {
        messagesJob?.cancel()
        _uiState.value = stateWithTransient(
            _uiState.value.copy(selectedConversationId = id, messages = emptyList(), unreadConversationIds = _uiState.value.unreadConversationIds - id),
            id, transientStates[id] ?: ConversationTransientState(),
        )
        messagesJob = scope.launch { repository.observeMessages(id).collect {
            if (_uiState.value.selectedConversationId == id) _uiState.value = _uiState.value.copy(messages = it)
        } }
    }

    fun newConversation(): String { var created = ""; scope.launch { created = repository.createConversation(); selectConversation(created) }; return created }
    fun deleteConversation(id: String) { scope.launch {
        repository.deleteConversation(id); transientStates.remove(id)
        _uiState.value = _uiState.value.copy(unreadConversationIds = _uiState.value.unreadConversationIds - id)
        if (_uiState.value.selectedConversationId == id) selectConversation(_uiState.value.conversations.firstOrNull { it.id != id }?.id ?: repository.createConversation())
    } }

    fun send(content: String) {
        val value = content.trim(); val id = _uiState.value.selectedConversationId ?: return
        val current = transientStates[id] ?: ConversationTransientState()
        if (value.isEmpty() || current.lifecycle == ChatGenerationLifecycle.QUEUED || current.lifecycle == ChatGenerationLifecycle.PROCESSING) return
        val replyId = current.replyTarget?.id
        enqueue(id) { repository.addMessage(id, MessageRole.USER, value, replyToMessageId = replyId) }
        updateTransient(id) { it.copy(draft = "", replyTarget = null) }
    }

    fun editAndSend(messageId: String, content: String) {
        val value = content.trim(); val id = _uiState.value.selectedConversationId ?: return
        val current = transientStates[id] ?: ConversationTransientState()
        if (value.isEmpty() || current.lifecycle == ChatGenerationLifecycle.QUEUED || current.lifecycle == ChatGenerationLifecycle.PROCESSING) return
        enqueue(id) { repository.editMessageAndDeleteAfter(id, messageId, value) }
    }

    private fun enqueue(id: String, prepare: suspend () -> Unit) {
        val prepared = CompletableDeferred<Unit>()
        generationQueue.addLast(GenerationRequest(id, settingsProvider(), prepared))
        updateTransient(id) { it.copy(lifecycle = ChatGenerationLifecycle.QUEUED, generationStartedAtElapsedMs = null, errorMessage = null) }
        _uiState.value = _uiState.value.copy(isRuntimeBusy = true)
        startProcessor()
        scope.launch { try { prepare(); prepared.complete(Unit) } catch (c: CancellationException) { prepared.cancel(c); throw c } catch (t: Throwable) { prepared.completeExceptionally(t) } }
    }

    private fun startProcessor() {
        if (processorJob?.isActive == true) return
        processorJob = scope.launch {
            while (generationQueue.isNotEmpty()) process(generationQueue.removeFirst())
            _uiState.value = _uiState.value.copy(isRuntimeBusy = false)
        }
    }

    private suspend fun process(request: GenerationRequest) {
        val id = request.conversationId; val startedAt = elapsedRealtimeMs()
        updateTransient(id) { it.copy(lifecycle = ChatGenerationLifecycle.PROCESSING, generationStartedAtElapsedMs = startedAt, errorMessage = null) }
        try {
            request.prepared.await()
            val context = repository.recentMessages(id, request.settings.historyWindow)
            val output = inference.generate(context, request.settings.maxOutputTokens, request.settings.enableThinking).trim()
            val duration = (elapsedRealtimeMs() - startedAt).coerceAtLeast(0)
            require(output.isNotEmpty()) { "Model boş çıktı döndürdü" }
            val assistant = repository.addMessage(id, MessageRole.ASSISTANT, output)
            _uiState.value = _uiState.value.copy(responseDurationsMs = _uiState.value.responseDurationsMs + (assistant.id to duration))
            updateTransient(id) { it.copy(lifecycle = ChatGenerationLifecycle.COMPLETED, generationStartedAtElapsedMs = null) }
            if (_uiState.value.selectedConversationId != id) _uiState.value = _uiState.value.copy(unreadConversationIds = _uiState.value.unreadConversationIds + id)
            completionNotifier.responseReady(id)
        } catch (c: CancellationException) {
            updateTransient(id) { it.copy(lifecycle = ChatGenerationLifecycle.IDLE, generationStartedAtElapsedMs = null) }; throw c
        } catch (t: Throwable) {
            updateTransient(id) { it.copy(lifecycle = ChatGenerationLifecycle.FAILED, generationStartedAtElapsedMs = null, errorMessage = t.message ?: "Yerel model çıkarımı başarısız") }
        }
    }

    fun delete(messageId: String) { scope.launch { repository.deleteMessage(messageId) } }
    fun edit(messageId: String, content: String) { val value = content.trim(); if (value.isNotEmpty()) scope.launch { repository.editMessage(messageId, value) } }
    fun fork(messageId: String): String { val source = _uiState.value.selectedConversationId ?: return ""; var target = ""; scope.launch { target = repository.forkConversation(source, messageId); selectConversation(target) }; return target }
    fun updateDraft(content: String) { _uiState.value.selectedConversationId?.let { id -> updateTransient(id) { it.copy(draft = content) } } }
    fun startReply(messageId: String) { val id = _uiState.value.selectedConversationId ?: return; val target = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return; updateTransient(id) { it.copy(replyTarget = target) } }
    fun cancelReply() { _uiState.value.selectedConversationId?.let { id -> updateTransient(id) { it.copy(replyTarget = null) } } }
    fun clearError() { _uiState.value.selectedConversationId?.let { id -> updateTransient(id) { it.copy(errorMessage = null) } } }

    private fun updateTransient(id: String, transform: (ConversationTransientState) -> ConversationTransientState) {
        val updated = transform(transientStates[id] ?: ConversationTransientState()); transientStates[id] = updated
        if (_uiState.value.selectedConversationId == id) _uiState.value = stateWithTransient(_uiState.value, id, updated)
    }
    private fun stateWithTransient(state: ChatUiState, id: String, transient: ConversationTransientState) = state.copy(
        draft = transient.draft, replyTarget = transient.replyTarget, generationLifecycle = transient.lifecycle,
        isGenerating = transient.lifecycle == ChatGenerationLifecycle.PROCESSING, isQueued = transient.lifecycle == ChatGenerationLifecycle.QUEUED,
        generatingConversationId = id.takeIf { transient.lifecycle == ChatGenerationLifecycle.PROCESSING },
        generationStartedAtElapsedMs = transient.generationStartedAtElapsedMs, errorMessage = transient.errorMessage,
    )

    companion object {
        const val CONTEXT_MESSAGE_LIMIT = 12; const val CHAT_MAX_TOKENS = 256
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as SmoothApplication
                return ChatViewModel(RoomChatRepository(AppDatabase.getDatabase(application).conversationDao()), app.chatInferenceGateway,
                    settingsProvider = { app.chatRuntimeSettings.settings.value }, completionNotifier = app.chatCompletionNotifier) as T
            }
        }
    }
}
