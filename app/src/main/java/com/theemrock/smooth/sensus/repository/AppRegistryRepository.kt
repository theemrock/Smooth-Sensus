package com.theemrock.smooth.sensus.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theemrock.smooth.sensus.data.AppDatabase
import com.theemrock.smooth.sensus.data.DiscoveredAppEntity
import com.theemrock.smooth.sensus.data.AppReviewStateEntity
import com.theemrock.smooth.sensus.data.AiEnrichmentEntity
import com.theemrock.smooth.sensus.data.CapabilityRegistryEntity
import com.theemrock.smooth.sensus.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRegistryRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val gson = Gson()

    suspend fun saveDiscoveredApps(apps: List<DiscoveredApp>) = withContext(Dispatchers.IO) {
        val entities = apps.map { app ->
            DiscoveredAppEntity(
                packageName = app.packageName,
                appLabel = app.appLabel,
                versionName = app.versionName,
                versionCode = app.versionCode,
                isSystemApp = app.isSystemApp,
                isEnabled = app.isEnabled,
                isLaunchable = app.isLaunchable,
                launcherActivity = app.launcherActivity,
                relevantPermissionsJson = gson.toJson(app.relevantPermissions),
                activitiesJson = gson.toJson(app.activities),
                servicesJson = gson.toJson(app.services),
                receiversJson = gson.toJson(app.receivers),
                intentFiltersJson = gson.toJson(app.intentFilters),
                androidDefaultRolesJson = gson.toJson(app.androidDefaultRoles),
                handlesIntentsJson = gson.toJson(app.handlesIntents),
                smoothPreferredRolesJson = gson.toJson(app.smoothPreferredRoles)
            )
        }
        db.appDiscoveryDao().insertDiscoveredApps(entities)
        
        refreshCapabilityRegistry()
    }

    suspend fun getAppRegistryItems(): List<AppRegistryItem> = withContext(Dispatchers.IO) {
        val discoveredEntities = db.appDiscoveryDao().getAllDiscoveredApps()
        val enrichmentEntities = db.aiEnrichmentDao().getAllEnrichments().associateBy { it.packageName }
        val reviewStates = db.appReviewStateDao().getAllReviewStates().associateBy { it.packageName }

        discoveredEntities.map { entity ->
            val stringListType = object : TypeToken<List<String>>() {}.type
            val app = DiscoveredApp(
                packageName = entity.packageName,
                appLabel = entity.appLabel,
                versionName = entity.versionName,
                versionCode = entity.versionCode,
                isSystemApp = entity.isSystemApp,
                isEnabled = entity.isEnabled,
                isLaunchable = entity.isLaunchable,
                launcherActivity = entity.launcherActivity,
                relevantPermissions = gson.fromJson(entity.relevantPermissionsJson, stringListType) ?: emptyList(),
                activities = gson.fromJson(entity.activitiesJson, stringListType) ?: emptyList(),
                services = gson.fromJson(entity.servicesJson, stringListType) ?: emptyList(),
                receivers = gson.fromJson(entity.receiversJson, stringListType) ?: emptyList(),
                intentFilters = gson.fromJson(entity.intentFiltersJson, stringListType) ?: emptyList(),
                androidDefaultRoles = gson.fromJson(entity.androidDefaultRolesJson, stringListType) ?: emptyList(),
                handlesIntents = gson.fromJson(entity.handlesIntentsJson, stringListType) ?: emptyList(),
                smoothPreferredRoles = gson.fromJson(entity.smoothPreferredRolesJson, stringListType) ?: emptyList()
            )

            val enrichmentEntity = enrichmentEntities[entity.packageName]
            val enrichment = enrichmentEntity?.let {
                val capType = object : TypeToken<List<AiCapabilityEnrichment>>() {}.type
                AiEnrichment(
                    packageName = it.packageName,
                    category = it.category,
                    role = it.role,
                    capabilities = gson.fromJson(it.capabilitiesJson, capType) ?: emptyList()
                )
            }

            AppRegistryItem(
                packageName = app.packageName,
                discoveredApp = app,
                aiEnrichment = enrichment,
                reviewState = reviewStates[entity.packageName]
                    ?.let { AppReviewState.valueOf(it.reviewState) }
                    ?: AppReviewState.UNREVIEWED
            )
        }
    }

    suspend fun updateAppReviewState(packageName: String, reviewState: AppReviewState) =
        withContext(Dispatchers.IO) {
            db.appReviewStateDao().upsertReviewState(
                AppReviewStateEntity(packageName, reviewState.name)
            )
        }

    suspend fun importAiEnrichments(enrichments: List<AiEnrichment>) = withContext(Dispatchers.IO) {
        val entities = enrichments.map {
            AiEnrichmentEntity(
                packageName = it.packageName,
                category = it.category,
                role = it.role,
                capabilitiesJson = gson.toJson(it.capabilities)
            )
        }
        db.aiEnrichmentDao().insertEnrichments(entities)
        refreshCapabilityRegistry()
    }

    suspend fun getCapabilityRegistryItems(): List<CapabilityRegistryItem> = withContext(Dispatchers.IO) {
        db.capabilityDao().getAllCapabilities().map {
            CapabilityRegistryItem(
                id = it.id,
                capabilityName = it.capabilityName,
                packageName = it.packageName,
                appLabel = it.appLabel,
                category = it.category,
                provenance = ProvenanceStatus.valueOf(it.provenanceStr),
                confidence = it.confidence,
                possibleAccessMethod = AccessMethod.valueOf(it.possibleAccessMethodStr),
                appFeatures = it.appFeatures
            )
        }
    }

    suspend fun refreshCapabilityRegistry() {
        val existingCapabilities = db.capabilityDao().getAllCapabilities()
        val apps = getAppRegistryItems()
        val capabilitiesList = mutableListOf<CapabilityRegistryEntity>()

        apps.forEach { item ->
            // 1. Technical Capabilities from Discovered App Metadata
            if (item.discoveredApp.isLaunchable) {
                capabilitiesList.add(
                    CapabilityRegistryEntity(
                        id = "${item.packageName}_LAUNCH",
                        capabilityName = "LAUNCHABLE_ACTIVITY",
                        packageName = item.packageName,
                        appLabel = item.discoveredApp.appLabel,
                        category = "SYSTEM_LAUNCHER",
                        provenanceStr = ProvenanceStatus.TECHNICALLY_DISCOVERED.name,
                        confidence = 1.0,
                        possibleAccessMethodStr = AccessMethod.EXPORTED_ACTIVITY.name,
                        appFeatures = "Launcher activity: ${item.discoveredApp.launcherActivity}"
                    )
                )
            }

            // 2. Add handlers info
            item.discoveredApp.handlesIntents.forEach { intentAction ->
                capabilitiesList.add(
                    CapabilityRegistryEntity(
                        id = "${item.packageName}_INTENT_${intentAction}",
                        capabilityName = "HANDLES_${intentAction.replace("android.intent.action.", "")}",
                        packageName = item.packageName,
                        appLabel = item.discoveredApp.appLabel,
                        category = "INTENT_HANDLER",
                        provenanceStr = ProvenanceStatus.TECHNICALLY_DISCOVERED.name,
                        confidence = 1.0,
                        possibleAccessMethodStr = AccessMethod.ANDROID_INTENT.name,
                        appFeatures = "Handles system intent action: $intentAction"
                    )
                )
            }

            // 3. AI Enrichment Capabilities
            item.aiEnrichment?.capabilities?.forEach { cap ->
                val id = "${item.packageName}_AI_${cap.capabilityName}"
                val matchedAccessMethod = when {
                    cap.possibleSmoothAccessMethods.contains("INTENT", ignoreCase = true) -> AccessMethod.ANDROID_INTENT
                    cap.possibleSmoothAccessMethods.contains("URI", ignoreCase = true) || cap.possibleSmoothAccessMethods.contains("LINK", ignoreCase = true) -> AccessMethod.DEEP_LINK_URI
                    cap.possibleSmoothAccessMethods.contains("MEDIA", ignoreCase = true) -> AccessMethod.MEDIA_SESSION
                    cap.possibleSmoothAccessMethods.contains("PROVIDER", ignoreCase = true) -> AccessMethod.CONTENT_PROVIDER
                    cap.possibleSmoothAccessMethods.contains("SERVICE", ignoreCase = true) -> AccessMethod.BOUND_SERVICE
                    cap.possibleSmoothAccessMethods.contains("ACTIVITY", ignoreCase = true) -> AccessMethod.EXPORTED_ACTIVITY
                    else -> AccessMethod.UNKNOWN
                }

                capabilitiesList.add(
                    CapabilityRegistryEntity(
                        id = id,
                        capabilityName = cap.capabilityName,
                        packageName = item.packageName,
                        appLabel = item.discoveredApp.appLabel,
                        category = item.aiEnrichment.category,
                        provenanceStr = ProvenanceStatus.AI_SUGGESTED.name,
                        confidence = cap.confidence,
                        possibleAccessMethodStr = matchedAccessMethod.name,
                        appFeatures = cap.appFeatures
                    )
                )
            }
        }

        val rebuiltCapabilities = preserveHumanConfirmedProvenance(
            existing = existingCapabilities,
            generated = capabilitiesList
        )
        db.capabilityDao().clearAllCapabilities()
        db.capabilityDao().insertCapabilities(rebuiltCapabilities)
    }

    suspend fun updateCapabilityStatus(id: String, newStatus: ProvenanceStatus) = withContext(Dispatchers.IO) {
        val all = db.capabilityDao().getAllCapabilities()
        val found = all.find { it.id == id }
        if (found != null) {
            db.capabilityDao().insertCapabilities(listOf(found.copy(provenanceStr = newStatus.name)))
        }
    }
}
