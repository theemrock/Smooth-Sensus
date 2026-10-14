package com.theemrock.smooth.sensus.normalization

import com.theemrock.smooth.sensus.model.AccessMethod
import com.theemrock.smooth.sensus.model.ActionPlanStatus
import com.theemrock.smooth.sensus.model.AppRegistryItem
import com.theemrock.smooth.sensus.model.AppReviewState
import com.theemrock.smooth.sensus.model.CapabilityRegistryItem
import com.theemrock.smooth.sensus.model.DiscoveredApp
import com.theemrock.smooth.sensus.model.Provider
import com.theemrock.smooth.sensus.model.ProvenanceStatus
import com.theemrock.smooth.sensus.scenario.ScenarioResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class ScenarioJsonParserIntegrationTest {
    @Test
    fun `raw JSON parses into scenario consumed by resolver`() {
        val request = "Spotify'ı aç, sonrakine geç"
        val rawOutput = """
            {
              "originalRequest": "$request",
              "goals": [
                {
                  "capabilityName": "APP_LAUNCH",
                  "scope": "APP",
                  "targetPackageName": "$SPOTIFY",
                  "arguments": {}
                },
                {
                  "capabilityName": "MEDIA_NEXT",
                  "scope": "SYSTEM",
                  "targetPackageName": null,
                  "arguments": {}
                }
              ]
            }
        """.trimIndent()
        val scenario = (ScenarioJsonParser().parse(rawOutput, request) as NormalizationResult.Success).scenario

        val plan = ScenarioResolver().resolve(
            scenario,
            listOf(spotifyApp()),
            listOf(verifiedSpotifyLaunch())
        )

        assertEquals(ActionPlanStatus.RESOLVED, plan.status)
        assertEquals(Provider.App(SPOTIFY, "${SPOTIFY}_LAUNCH"), plan.steps[0].provider)
        assertEquals(Provider.System("MEDIA_SESSION"), plan.steps[1].provider)
    }

    private fun spotifyApp() = AppRegistryItem(
        packageName = SPOTIFY,
        discoveredApp = DiscoveredApp(
            packageName = SPOTIFY,
            appLabel = "Spotify",
            versionName = "1",
            versionCode = 1,
            isSystemApp = false,
            isEnabled = true,
            isLaunchable = true,
            launcherActivity = "MainActivity",
            relevantPermissions = emptyList(),
            activities = emptyList(),
            services = emptyList(),
            receivers = emptyList(),
            intentFilters = emptyList(),
            androidDefaultRoles = emptyList(),
            handlesIntents = emptyList()
        ),
        reviewState = AppReviewState.SELECT
    )

    private fun verifiedSpotifyLaunch() = CapabilityRegistryItem(
        id = "${SPOTIFY}_LAUNCH",
        capabilityName = "LAUNCHABLE_ACTIVITY",
        packageName = SPOTIFY,
        appLabel = "Spotify",
        category = "SYSTEM_LAUNCHER",
        provenance = ProvenanceStatus.TECHNICALLY_VERIFIED,
        confidence = 1.0,
        possibleAccessMethod = AccessMethod.EXPORTED_ACTIVITY,
        appFeatures = "Launcher activity: MainActivity"
    )

    companion object {
        private const val SPOTIFY = "com.spotify.music"
    }
}
