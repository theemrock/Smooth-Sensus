package com.theemrock.smooth.sensus.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "discovered_apps")
data class DiscoveredAppEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val isLaunchable: Boolean,
    val launcherActivity: String,
    val relevantPermissionsJson: String,
    val activitiesJson: String,
    val servicesJson: String,
    val receiversJson: String,
    val intentFiltersJson: String,
    val androidDefaultRolesJson: String,
    val handlesIntentsJson: String,
    val smoothPreferredRolesJson: String
)

@Entity(tableName = "app_review_states")
data class AppReviewStateEntity(
    @PrimaryKey val packageName: String,
    val reviewState: String
)

@Entity(tableName = "ai_enrichments")
data class AiEnrichmentEntity(
    @PrimaryKey val packageName: String,
    val category: String,
    val role: String,
    val capabilitiesJson: String
)

@Entity(tableName = "capability_items")
data class CapabilityRegistryEntity(
    @PrimaryKey val id: String,
    val capabilityName: String,
    val packageName: String,
    val appLabel: String,
    val category: String,
    val provenanceStr: String,
    val confidence: Double,
    val possibleAccessMethodStr: String,
    val appFeatures: String
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val createdAt: Long
)

@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["conversationId", "ordinal"], unique = true)
    ]
)
data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val ordinal: Long,
    val inputMode: String,
    val replyToMessageId: String?
)
