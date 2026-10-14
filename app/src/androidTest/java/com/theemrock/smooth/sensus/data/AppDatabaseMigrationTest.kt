package com.theemrock.smooth.sensus.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val databaseName = "migration-test"

    @Test
    fun migrate1To2CreatesEmptyReviewStateTable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val factory = FrameworkSQLiteOpenHelperFactory()
        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).also {
            it.writableDatabase
            it.close()
        }

        val migrated = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_1_2.migrate(db)
                    }
                })
                .build()
        )
        migrated.writableDatabase.query("SELECT COUNT(*) FROM app_review_states").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrate2To3CreatesConversationTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val factory = FrameworkSQLiteOpenHelperFactory()
        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).also { it.writableDatabase; it.close() }

        val migrated = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_2_3.migrate(db)
                    }
                })
                .build()
        )
        migrated.writableDatabase.query("SELECT COUNT(*) FROM conversations").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.writableDatabase.query("SELECT COUNT(*) FROM conversation_messages").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrate3To4BackfillsConversationLocalOrdinals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val factory = FrameworkSQLiteOpenHelperFactory()
        val old = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        MIGRATION_2_3.migrate(db)
                        db.execSQL("INSERT INTO conversations(id, createdAt) VALUES ('A', 1), ('B', 1)")
                        db.execSQL("INSERT INTO conversation_messages(id, conversationId, role, content, timestamp, inputMode, replyToMessageId) VALUES " +
                            "('z', 'A', 'USER', 'A1', 10, 'TEXT', NULL), " +
                            "('a', 'B', 'USER', 'B1', 10, 'TEXT', NULL), " +
                            "('y', 'A', 'USER', 'A2', 10, 'TEXT', NULL), " +
                            "('b', 'B', 'USER', 'B2', 20, 'TEXT', NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        old.writableDatabase; old.close()

        val migrated = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = MIGRATION_3_4.migrate(db)
                }).build()
        )
        migrated.writableDatabase.query("SELECT conversationId, ordinal FROM conversation_messages ORDER BY conversationId, ordinal").use { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.moveToNext()) rows += "${cursor.getString(0)}:${cursor.getLong(1)}"
            assertEquals(listOf("A:1", "A:2", "B:1", "B:2"), rows)
        }
        migrated.close(); context.deleteDatabase(databaseName)
    }
}
