package com.theemrock.smooth.sensus.conversation

import com.theemrock.smooth.sensus.data.ConversationDao
import com.theemrock.smooth.sensus.data.ConversationEntity
import com.theemrock.smooth.sensus.data.ConversationMessageEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

enum class MessageRole { USER, ASSISTANT }
enum class MessageInputMode { TEXT, VOICE }

data class ChatMessage(val id: String, val conversationId: String, val role: MessageRole, val content: String, val timestamp: Long, val inputMode: MessageInputMode, val replyToMessageId: String?)
data class ChatConversation(val id: String, val title: String, val createdAt: Long, val updatedAt: Long)

fun interface ChatInferenceGateway {
    suspend fun generate(messages: List<ChatMessage>, maxTokens: Int, enableThinking: Boolean): String
}

interface ChatRepository {
    fun observeConversations(): Flow<List<ChatConversation>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun createConversation(): String
    suspend fun deleteConversation(conversationId: String)
    suspend fun addMessage(conversationId: String, role: MessageRole, content: String, inputMode: MessageInputMode = MessageInputMode.TEXT, replyToMessageId: String? = null): ChatMessage
    suspend fun editMessage(messageId: String, content: String)
    suspend fun editMessageAndDeleteAfter(conversationId: String, messageId: String, content: String): ChatMessage
    suspend fun deleteMessage(messageId: String)
    suspend fun forkConversation(conversationId: String, throughMessageId: String): String
    suspend fun recentMessages(conversationId: String, limit: Int): List<ChatMessage>
}

class RoomChatRepository(private val dao: ConversationDao, private val now: () -> Long = System::currentTimeMillis, private val newId: () -> String = { UUID.randomUUID().toString() }) : ChatRepository {
    override fun observeConversations(): Flow<List<ChatConversation>> =
        dao.observeConversations().combine(dao.observeMessagesForAll()) { conversations, messages ->
            conversations.map { conversation ->
                val own = messages.filter { it.conversationId == conversation.id }
                ChatConversation(conversation.id, own.firstOrNull { it.role == MessageRole.USER.name }?.content?.take(40) ?: "Yeni sohbet", conversation.createdAt, own.maxOfOrNull { it.timestamp } ?: conversation.createdAt)
            }.sortedByDescending { it.updatedAt }
        }
    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = dao.observeMessages(conversationId).map { it.map(::toDomain) }
    override suspend fun createConversation(): String = newId().also { dao.insertConversation(ConversationEntity(it, now())) }
    override suspend fun deleteConversation(conversationId: String) { dao.deleteConversation(conversationId) }
    override suspend fun addMessage(conversationId: String, role: MessageRole, content: String, inputMode: MessageInputMode, replyToMessageId: String?): ChatMessage {
        val timestamp = now(); dao.insertConversation(ConversationEntity(conversationId, timestamp))
        val message = ChatMessage(newId(), conversationId, role, content, timestamp, inputMode, replyToMessageId)
        return toDomain(dao.insertMessageAtEnd(message.toEntity()))
    }
    override suspend fun editMessage(messageId: String, content: String) { dao.updateMessageContent(messageId, content) }
    override suspend fun editMessageAndDeleteAfter(conversationId: String, messageId: String, content: String): ChatMessage =
        toDomain(requireNotNull(dao.updateMessageAndDeleteAfter(conversationId, messageId, content)) { "Message not found in conversation" })
    override suspend fun deleteMessage(messageId: String) { dao.deleteMessage(messageId) }
    override suspend fun forkConversation(conversationId: String, throughMessageId: String): String {
        val source = dao.getMessages(conversationId); val selectedIndex = source.indexOfFirst { it.id == throughMessageId }
        require(selectedIndex >= 0) { "Fork message not found" }
        val target = createConversation()
        source.take(selectedIndex + 1).forEach { dao.insertMessageAtEnd(it.copy(id = newId(), conversationId = target, ordinal = 0, replyToMessageId = null)) }
        return target
    }
    override suspend fun recentMessages(conversationId: String, limit: Int): List<ChatMessage> {
        val all = dao.getMessages(conversationId)
        val recent = all.takeLast(limit).toMutableList()
        val present = recent.mapTo(mutableSetOf()) { it.id }
        recent.mapNotNull { it.replyToMessageId }.distinct().forEach { replyId ->
            all.firstOrNull { it.id == replyId && it.id !in present }?.let { recent.add(0, it); present += it.id }
        }
        return recent.map(::toDomain)
    }
    private fun toDomain(m: ConversationMessageEntity) = ChatMessage(m.id, m.conversationId, MessageRole.valueOf(m.role), m.content, m.timestamp, MessageInputMode.valueOf(m.inputMode), m.replyToMessageId)
    private fun ChatMessage.toEntity() = ConversationMessageEntity(id, conversationId, role.name, content, timestamp, 0, inputMode.name, replyToMessageId)
}
