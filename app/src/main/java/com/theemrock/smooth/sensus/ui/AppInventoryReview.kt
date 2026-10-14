package com.theemrock.smooth.sensus.ui

import com.theemrock.smooth.sensus.model.AppRegistryItem
import com.theemrock.smooth.sensus.model.AppReviewState
import com.theemrock.smooth.sensus.model.DiscoveredApp

enum class ReviewFilter {
    ALL,
    UNREVIEWED,
    SELECT,
    IGNORE,
    UNKNOWN
}

enum class AppTypeFilter {
    ALL,
    USER,
    SYSTEM
}

data class AppLaunchTarget(val packageName: String, val launcherActivity: String)

fun launchTargetFor(app: DiscoveredApp): AppLaunchTarget? =
    if (app.isLaunchable && app.launcherActivity.isNotBlank()) {
        AppLaunchTarget(app.packageName, app.launcherActivity)
    } else {
        null
    }

fun countAppTypePopulation(items: List<AppRegistryItem>, appTypeFilter: AppTypeFilter): Int =
    items.count { item ->
        when (appTypeFilter) {
            AppTypeFilter.ALL -> true
            AppTypeFilter.USER -> !item.discoveredApp.isSystemApp
            AppTypeFilter.SYSTEM -> item.discoveredApp.isSystemApp
        }
    }

fun countReviewStates(items: List<AppRegistryItem>): Map<AppReviewState, Int> =
    AppReviewState.entries.associateWith { state -> items.count { it.reviewState == state } }

fun filterAppInventory(
    items: List<AppRegistryItem>,
    query: String,
    reviewFilter: ReviewFilter,
    appTypeFilter: AppTypeFilter
): List<AppRegistryItem> {
    val normalizedQuery = query.trim()
    return items.filter { item ->
        val matchesQuery = normalizedQuery.isEmpty() ||
            item.discoveredApp.appLabel.contains(normalizedQuery, ignoreCase = true) ||
            item.packageName.contains(normalizedQuery, ignoreCase = true)
        val matchesReview = reviewFilter == ReviewFilter.ALL ||
            item.reviewState.name == reviewFilter.name
        val matchesType = when (appTypeFilter) {
            AppTypeFilter.ALL -> true
            AppTypeFilter.USER -> !item.discoveredApp.isSystemApp
            AppTypeFilter.SYSTEM -> item.discoveredApp.isSystemApp
        }
        matchesQuery && matchesReview && matchesType
    }
}
