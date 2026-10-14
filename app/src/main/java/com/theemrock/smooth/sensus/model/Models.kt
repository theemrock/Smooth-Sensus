package com.theemrock.smooth.sensus.model

data class DiscoveredApp(
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val isLaunchable: Boolean,
    val launcherActivity: String,
    val relevantPermissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val intentFilters: List<String>,
    val androidDefaultRoles: List<String>,
    val handlesIntents: List<String>,
    val smoothPreferredRoles: List<String> = emptyList()
)

data class AiEnrichment(
    val packageName: String,
    val category: String,
    val role: String,
    val capabilities: List<AiCapabilityEnrichment>
)

data class AiCapabilityEnrichment(
    val capabilityName: String,
    val appFeatures: String,
    val possibleSmoothAccessMethods: String,
    val confidence: Double
)

enum class ProvenanceStatus {
    TECHNICALLY_DISCOVERED,
    AI_SUGGESTED,
    HUMAN_CONFIRMED,
    TECHNICALLY_VERIFIED,
    UNKNOWN
}

enum class AccessMethod {
    ANDROID_INTENT,
    DEEP_LINK_URI,
    MEDIA_SESSION,
    CONTENT_PROVIDER,
    BOUND_SERVICE,
    EXPORTED_ACTIVITY,
    OFFICIAL_API,
    ACCESSIBILITY,
    UNKNOWN
}

enum class AppReviewState {
    UNREVIEWED,
    SELECT,
    IGNORE,
    UNKNOWN
}

data class CapabilityRegistryItem(
    val id: String, // e.g., packageName + "_" + capabilityName
    val capabilityName: String,
    val packageName: String,
    val appLabel: String,
    val category: String,
    val provenance: ProvenanceStatus,
    val confidence: Double,
    val possibleAccessMethod: AccessMethod,
    val appFeatures: String
)

data class AppRegistryItem(
    val packageName: String,
    val discoveredApp: DiscoveredApp,
    val aiEnrichment: AiEnrichment? = null,
    val humanConfirmedCapabilities: List<String> = emptyList(),
    val reviewState: AppReviewState = AppReviewState.UNREVIEWED
)
