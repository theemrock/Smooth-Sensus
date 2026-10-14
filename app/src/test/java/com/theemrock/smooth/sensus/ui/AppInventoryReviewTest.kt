package com.theemrock.smooth.sensus.ui

import com.theemrock.smooth.sensus.model.AppRegistryItem
import com.theemrock.smooth.sensus.model.AppReviewState
import com.theemrock.smooth.sensus.model.DiscoveredApp
import org.junit.Assert.assertEquals
import org.junit.Test

class AppInventoryReviewTest {
    @Test
    fun missingReviewRowIsRepresentedAsUnreviewed() {
        val item = registryItem("com.example.new")

        assertEquals(AppReviewState.UNREVIEWED, item.reviewState)
    }

    @Test
    fun countsEveryReviewStateSeparately() {
        val items = listOf(
            registryItem("one", AppReviewState.UNREVIEWED),
            registryItem("two", AppReviewState.SELECT),
            registryItem("three", AppReviewState.IGNORE),
            registryItem("four", AppReviewState.UNKNOWN),
            registryItem("five", AppReviewState.UNKNOWN)
        )

        assertEquals(
            mapOf(
                AppReviewState.UNREVIEWED to 1,
                AppReviewState.SELECT to 1,
                AppReviewState.IGNORE to 1,
                AppReviewState.UNKNOWN to 2
            ),
            countReviewStates(items)
        )
    }

    @Test
    fun filtersByReviewStateAppTypeAndSearchText() {
        val items = listOf(
            registryItem("com.example.music", AppReviewState.UNREVIEWED, label = "Audio Player", isSystem = false),
            registryItem("com.android.music", AppReviewState.UNREVIEWED, label = "System Music", isSystem = true),
            registryItem("com.example.maps", AppReviewState.SELECT, label = "Maps", isSystem = false)
        )

        val filtered = filterAppInventory(
            items = items,
            query = "example.music",
            reviewFilter = ReviewFilter.UNREVIEWED,
            appTypeFilter = AppTypeFilter.USER
        )

        assertEquals(listOf("com.example.music"), filtered.map { it.packageName })
    }

    @Test
    fun searchFindsReviewedAppInsideIgnoreFilterByLabel() {
        val items = listOf(
            registryItem("org.videolan.vlc", AppReviewState.IGNORE, label = "VLC"),
            registryItem("com.example.other", AppReviewState.IGNORE, label = "Other"),
            registryItem("com.example.vlc.remote", AppReviewState.SELECT, label = "Remote")
        )

        val filtered = filterAppInventory(
            items = items,
            query = "VLC",
            reviewFilter = ReviewFilter.IGNORE,
            appTypeFilter = AppTypeFilter.ALL
        )

        assertEquals(listOf("org.videolan.vlc"), filtered.map { it.packageName })
    }

    @Test
    fun appTypePopulationCountIgnoresSearchAndReviewFilters() {
        val items = listOf(
            registryItem("user.one", AppReviewState.SELECT, isSystem = false),
            registryItem("user.two", AppReviewState.IGNORE, isSystem = false),
            registryItem("system.one", AppReviewState.UNREVIEWED, isSystem = true)
        )

        assertEquals(3, countAppTypePopulation(items, AppTypeFilter.ALL))
        assertEquals(2, countAppTypePopulation(items, AppTypeFilter.USER))
        assertEquals(1, countAppTypePopulation(items, AppTypeFilter.SYSTEM))
    }

    @Test
    fun launchTargetRequiresLaunchableAppAndLauncherActivity() {
        val launchable = registryItem("org.videolan.vlc").discoveredApp.copy(
            launcherActivity = "org.videolan.vlc.StartActivity"
        )
        val notLaunchable = launchable.copy(isLaunchable = false)

        assertEquals(
            AppLaunchTarget("org.videolan.vlc", "org.videolan.vlc.StartActivity"),
            launchTargetFor(launchable)
        )
        assertEquals(null, launchTargetFor(notLaunchable))
        assertEquals(null, launchTargetFor(launchable.copy(launcherActivity = "")))
    }

    private fun registryItem(
        packageName: String,
        reviewState: AppReviewState = AppReviewState.UNREVIEWED,
        label: String = packageName,
        isSystem: Boolean = false
    ) = AppRegistryItem(
        packageName = packageName,
        discoveredApp = DiscoveredApp(
            packageName = packageName,
            appLabel = label,
            versionName = "1",
            versionCode = 1,
            isSystemApp = isSystem,
            isEnabled = true,
            isLaunchable = true,
            launcherActivity = "",
            relevantPermissions = emptyList(),
            activities = emptyList(),
            services = emptyList(),
            receivers = emptyList(),
            intentFilters = emptyList(),
            androidDefaultRoles = emptyList(),
            handlesIntents = emptyList()
        ),
        reviewState = reviewState
    )
}
