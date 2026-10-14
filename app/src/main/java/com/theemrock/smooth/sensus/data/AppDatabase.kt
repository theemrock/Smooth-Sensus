package com.theemrock.smooth.sensus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `app_review_states` (
                `packageName` TEXT NOT NULL,
                `reviewState` TEXT NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `conversations` " +
                "(`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `conversation_messages` (" +
                "`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `inputMode` TEXT NOT NULL, " +
                "`replyToMessageId` TEXT, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_messages_conversationId` " +
                "ON `conversation_messages` (`conversationId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_messages_conversationId_timestamp` " +
                "ON `conversation_messages` (`conversationId`, `timestamp`)"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversation_messages` ADD COLUMN `ordinal` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `conversation_messages` AS target SET `ordinal` = (" +
                "SELECT COUNT(*) FROM `conversation_messages` AS prior " +
                "WHERE prior.`conversationId` = target.`conversationId` AND (" +
                "prior.`timestamp` < target.`timestamp` OR " +
                "(prior.`timestamp` = target.`timestamp` AND prior.`id` <= target.`id`)))"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_messages_conversationId_ordinal` " +
                "ON `conversation_messages` (`conversationId`, `ordinal`)"
        )
    }
}

@Database(
    entities = [
        DiscoveredAppEntity::class,
        AppReviewStateEntity::class,
        AiEnrichmentEntity::class,
        CapabilityRegistryEntity::class,
        ConversationEntity::class,
        ConversationMessageEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDiscoveryDao(): AppDiscoveryDao
    abstract fun appReviewStateDao(): AppReviewStateDao
    abstract fun aiEnrichmentDao(): AiEnrichmentDao
    abstract fun capabilityDao(): CapabilityDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smooth_learn_scenario_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
