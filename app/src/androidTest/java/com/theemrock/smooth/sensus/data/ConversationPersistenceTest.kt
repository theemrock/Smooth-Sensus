package com.theemrock.smooth.sensus.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.theemrock.smooth.sensus.conversation.MessageRole
import com.theemrock.smooth.sensus.conversation.RoomChatRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPersistenceTest {
    @Test
    fun editAndDeleteAfterUsesConversationLocalOrdinalWithInterleavedRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "conversation-truncate-safety-test"; context.deleteDatabase(name)
        val ids = ArrayDeque(listOf("A", "B", "A1", "B1", "A2", "B2", "A3", "B3"))
        val database = openDatabase(context, name)
        try {
            val repository = RoomChatRepository(database.conversationDao(), now = { 100L }, newId = { ids.removeFirst() })
            val conversationA = repository.createConversation(); val conversationB = repository.createConversation()
            repository.addMessage(conversationA, MessageRole.USER, "A1")
            repository.addMessage(conversationB, MessageRole.USER, "B1")
            val a2 = repository.addMessage(conversationA, MessageRole.USER, "A2")
            repository.addMessage(conversationB, MessageRole.USER, "B2")
            repository.addMessage(conversationA, MessageRole.ASSISTANT, "A3")
            repository.addMessage(conversationB, MessageRole.ASSISTANT, "B3")

            val edited = repository.editMessageAndDeleteAfter(conversationA, a2.id, "A2-edited")

            assertEquals(conversationA, edited.conversationId)
            assertEquals(listOf("A1", "A2-edited"), repository.recentMessages(conversationA, 20).map { it.content })
            assertEquals(listOf("B1", "B2", "B3"), repository.recentMessages(conversationB, 20).map { it.content })
        } finally { database.close() }

        val reopened = openDatabase(context, name)
        try {
            assertEquals(listOf("A1", "A2-edited"), reopened.conversationDao().getMessages("A").map { it.content })
            assertEquals(listOf("B1", "B2", "B3"), reopened.conversationDao().getMessages("B").map { it.content })
        } finally { reopened.close(); context.deleteDatabase(name) }
    }

    @Test
    fun multipleConversationsEditReplyAndForkPersistIndependently() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "conversation-m07b-test"; context.deleteDatabase(name)
        val ids = ArrayDeque(listOf("conversation-1", "message-a", "message-b", "message-c", "fork", "copy-a", "copy-b", "copy-c", "reply"))
        var clock = 0L
        val database = openDatabase(context, name)
        try {
            val repository = RoomChatRepository(database.conversationDao(), now = { ++clock }, newId = { ids.removeFirst() })
            val original = repository.createConversation()
            val a = repository.addMessage(original, MessageRole.USER, "A")
            val b = repository.addMessage(original, MessageRole.ASSISTANT, "B")
            val c = repository.addMessage(original, MessageRole.USER, "C", replyToMessageId = a.id)
            repository.editMessage(b.id, "B2")
            val fork = repository.forkConversation(original, c.id)
            repository.deleteMessage(b.id)

            assertEquals(listOf("A", "C"), repository.recentMessages(original, 20).map { it.content })
            assertEquals(a.id, repository.recentMessages(original, 20).last().replyToMessageId)
            assertEquals(listOf("A", "C"), repository.recentMessages(original, 1).map { it.content })
            assertEquals(listOf("A", "B2", "C"), repository.recentMessages(fork, 20).map { it.content })
        } finally { database.close() }

        val reopened = openDatabase(context, name)
        try {
            assertEquals(listOf("A", "C"), reopened.conversationDao().getMessages("conversation-1").map { it.content })
            assertEquals(listOf("A", "B2", "C"), reopened.conversationDao().getMessages("fork").map { it.content })
        } finally { reopened.close(); context.deleteDatabase(name) }
    }

    @Test
    fun deletingOneMessageSurvivesDatabaseReopenWithoutDeletingLaterMessages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "conversation-persistence-test"
        context.deleteDatabase(databaseName)

        val firstDatabase = openDatabase(context, databaseName)
        try {
            val database = firstDatabase
            val dao = database.conversationDao()
            dao.insertConversation(ConversationEntity("default", 1L))
            dao.insertMessage(message("one", 1L))
            dao.insertMessage(message("two", 2L))
            dao.insertMessage(message("three", 3L))
            assertEquals(1, dao.deleteMessage("two"))
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = openDatabase(context, databaseName)
        try {
            val database = reopenedDatabase
            assertEquals(
                listOf("one", "three"),
                database.conversationDao().observeMessages("default").first().map { it.id }
            )
        } finally {
            reopenedDatabase.close()
        }
        context.deleteDatabase(databaseName)
        Unit
    }

    private fun openDatabase(context: Context, name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    private fun message(id: String, timestamp: Long) = ConversationMessageEntity(
        id = id,
        conversationId = "default",
        role = "USER",
        content = id,
        timestamp = timestamp,
        ordinal = timestamp,
        inputMode = "TEXT",
        replyToMessageId = null
    )
}
