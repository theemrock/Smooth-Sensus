package com.theemrock.smooth.sensus.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDiscoveryDao {
    @Query("SELECT * FROM discovered_apps")
    suspend fun getAllDiscoveredApps(): List<DiscoveredAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiscoveredApps(apps: List<DiscoveredAppEntity>)

    @Query("DELETE FROM discovered_apps")
    suspend fun clearAllDiscoveredApps()
}

@Dao
interface AppReviewStateDao {
    @Query("SELECT * FROM app_review_states")
    suspend fun getAllReviewStates(): List<AppReviewStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReviewState(reviewState: AppReviewStateEntity)
}

@Dao
interface AiEnrichmentDao {
    @Query("SELECT * FROM ai_enrichments")
    suspend fun getAllEnrichments(): List<AiEnrichmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnrichments(enrichments: List<AiEnrichmentEntity>)
}

@Dao
interface CapabilityDao {
    @Query("SELECT * FROM capability_items")
    suspend fun getAllCapabilities(): List<CapabilityRegistryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCapabilities(capabilities: List<CapabilityRegistryEntity>)

    @Query("DELETE FROM capability_items")
    suspend fun clearAllCapabilities()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation_messages")
    fun observeMessagesForAll(): Flow<List<ConversationMessageEntity>>

    @Query(
        "SELECT * FROM conversation_messages WHERE conversationId = :conversationId " +
            "ORDER BY ordinal ASC"
    )
    fun observeMessages(conversationId: String): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :conversationId ORDER BY ordinal ASC")
    suspend fun getMessages(conversationId: String): List<ConversationMessageEntity>

    @Query("SELECT COALESCE(MAX(ordinal), 0) FROM conversation_messages WHERE conversationId = :conversationId")
    suspend fun maxOrdinal(conversationId: String): Long

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :conversationId AND id = :messageId LIMIT 1")
    suspend fun getMessage(conversationId: String, messageId: String): ConversationMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert
    suspend fun insertMessage(message: ConversationMessageEntity)

    @Transaction
    suspend fun insertMessageAtEnd(message: ConversationMessageEntity): ConversationMessageEntity {
        val sequenced = message.copy(ordinal = maxOrdinal(message.conversationId) + 1)
        insertMessage(sequenced)
        return sequenced
    }

    @Query("UPDATE conversation_messages SET content = :content WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: String, content: String): Int

    @Query("UPDATE conversation_messages SET content = :content WHERE conversationId = :conversationId AND id = :messageId")
    suspend fun updateMessageContentInConversation(conversationId: String, messageId: String, content: String): Int

    @Query("DELETE FROM conversation_messages WHERE conversationId = :conversationId AND ordinal > :ordinal")
    suspend fun deleteAfterOrdinal(conversationId: String, ordinal: Long): Int

    @Transaction
    suspend fun updateMessageAndDeleteAfter(conversationId: String, messageId: String, content: String): ConversationMessageEntity? {
        val selected = getMessage(conversationId, messageId) ?: return null
        if (selected.role != "USER") return null
        if (updateMessageContentInConversation(conversationId, messageId, content) != 1) return null
        deleteAfterOrdinal(conversationId, selected.ordinal)
        return selected.copy(content = content)
    }

    @Query("DELETE FROM conversation_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String): Int

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String): Int
}
