package com.theemrock.smooth.sensus.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.theemrock.smooth.sensus.model.AppRegistryItem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val inventoryTimestampFormatter = DateTimeFormatter.ofPattern("yyMMddHHmm")

fun inventoryFilename(extension: String, time: LocalDateTime = LocalDateTime.now()): String =
    "inv_${time.format(inventoryTimestampFormatter)}.$extension"

fun buildInventoryJson(appItems: List<AppRegistryItem>): String {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val records = JsonArray()
    appItems.forEach { item ->
        val record = gson.toJsonTree(item.discoveredApp).asJsonObject
        record.addProperty("reviewState", item.reviewState.name)
        records.add(record)
    }
    return gson.toJson(records)
}

fun buildInventoryText(appItems: List<AppRegistryItem>): String =
    appItems.joinToString("\n") { item ->
        val app = item.discoveredApp
        listOf(
            "appLabel=${app.appLabel.compactExportField()}",
            "packageName=${app.packageName.compactExportField()}",
            "versionName=${app.versionName.compactExportField()}",
            "isSystemApp=${app.isSystemApp}",
            "isEnabled=${app.isEnabled}",
            "isLaunchable=${app.isLaunchable}",
            "launcherActivity=${app.launcherActivity.compactExportField()}",
            "androidDefaultRoles=${app.androidDefaultRoles.joinToString(",").compactExportField()}",
            "handlesIntents=${app.handlesIntents.joinToString(",").compactExportField()}",
            "reviewState=${item.reviewState.name}"
        ).joinToString("\t")
    }

private fun String.compactExportField(): String = replace(Regex("[\\r\\n\\t]+"), " ")
