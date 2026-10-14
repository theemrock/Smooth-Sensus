package com.theemrock.smooth.sensus.ui

import com.google.gson.JsonParser
import com.theemrock.smooth.sensus.model.AppRegistryItem
import com.theemrock.smooth.sensus.model.AppReviewState
import com.theemrock.smooth.sensus.model.DiscoveredApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class InventoryExportTest {
    @Test
    fun jsonExportAddsPersistedReviewStateToFlatAppRecord() {
        val json = buildInventoryJson(listOf(item(AppReviewState.UNKNOWN)))
        val record = JsonParser.parseString(json).asJsonArray[0].asJsonObject

        assertEquals("com.example.player", record["packageName"].asString)
        assertEquals("UNKNOWN", record["reviewState"].asString)
        assertTrue(record.has("handlesIntents"))
    }

    @Test
    fun textExportAddsPersistedReviewStateToAppRecord() {
        val text = buildInventoryText(listOf(item(AppReviewState.SELECT)))

        assertTrue(text.contains("packageName=com.example.player"))
        assertTrue(text.contains("\treviewState=SELECT"))
    }

    @Test
    fun filenameUsesShortDeviceLocalTimestamp() {
        val time = LocalDateTime.of(2026, 9, 25, 12, 51)

        assertEquals("inv_2609251251.txt", inventoryFilename("txt", time))
        assertEquals("inv_2609251251.json", inventoryFilename("json", time))
    }

    private fun item(reviewState: AppReviewState) = AppRegistryItem(
        packageName = "com.example.player",
        discoveredApp = DiscoveredApp(
            packageName = "com.example.player",
            appLabel = "Player",
            versionName = "1.0",
            versionCode = 1,
            isSystemApp = false,
            isEnabled = true,
            isLaunchable = true,
            launcherActivity = "com.example.player.MainActivity",
            relevantPermissions = listOf("android.permission.INTERNET"),
            activities = listOf("com.example.player.MainActivity"),
            services = emptyList(),
            receivers = emptyList(),
            intentFilters = emptyList(),
            androidDefaultRoles = emptyList(),
            handlesIntents = listOf("android.intent.action.VIEW")
        ),
        reviewState = reviewState
    )
}
