package com.theemrock.smooth.sensus.discovery

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.theemrock.smooth.sensus.model.DiscoveredApp

class AppDiscoveryManager(private val context: Context) {

    fun scanInstalledApps(): List<DiscoveredApp> {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PERMISSIONS

        val packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))

        return packages.mapNotNull { packageInfo ->
            val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isEnabled = appInfo.enabled
            
            val launcherIntent = pm.getLaunchIntentForPackage(packageInfo.packageName)
            val isLaunchable = launcherIntent != null
            val launcherActivity = launcherIntent?.component?.className ?: ""

            DiscoveredApp(
                packageName = packageInfo.packageName,
                appLabel = pm.getApplicationLabel(appInfo).toString(),
                versionName = packageInfo.versionName ?: "unknown",
                versionCode = packageInfo.longVersionCode,
                isSystemApp = isSystemApp,
                isEnabled = isEnabled,
                isLaunchable = isLaunchable,
                launcherActivity = launcherActivity,
                relevantPermissions = packageInfo.requestedPermissions?.toList() ?: emptyList(),
                activities = packageInfo.activities?.map { it.name } ?: emptyList(),
                services = packageInfo.services?.map { it.name } ?: emptyList(),
                receivers = packageInfo.receivers?.map { it.name } ?: emptyList(),
                intentFilters = emptyList(), // Technical limitation for general apps
                androidDefaultRoles = emptyList(), // Placeholder for future role detection
                handlesIntents = discoverHandledIntents(packageInfo.packageName),
                smoothPreferredRoles = emptyList()
            )
        }
    }

    private fun discoverHandledIntents(packageName: String): List<String> {
        val pm = context.packageManager
        val commonIntents = listOf(
            Intent.ACTION_VIEW,
            Intent.ACTION_SEND,
            Intent.ACTION_EDIT,
            Intent.ACTION_PICK,
            Intent.ACTION_MAIN
        )
        
        val handledActions = mutableSetOf<String>()
        
        commonIntents.forEach { action ->
            val intent = Intent(action)
            intent.`package` = packageName
            val resolveInfo = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            if (resolveInfo.isNotEmpty()) {
                handledActions.add(action)
            }
        }
        
        return handledActions.toList()
    }
}
