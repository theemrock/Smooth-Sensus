package com.theemrock.smooth.sensus.scenario

import com.theemrock.smooth.sensus.model.AccessMethod
import com.theemrock.smooth.sensus.model.ActionPlanStatus
import com.theemrock.smooth.sensus.model.AppRegistryItem
import com.theemrock.smooth.sensus.model.AppReviewState
import com.theemrock.smooth.sensus.model.CapabilityRegistryItem
import com.theemrock.smooth.sensus.model.CapabilityScope
import com.theemrock.smooth.sensus.model.DiscoveredApp
import com.theemrock.smooth.sensus.model.Goal
import com.theemrock.smooth.sensus.model.Provider
import com.theemrock.smooth.sensus.model.ProvenanceStatus
import com.theemrock.smooth.sensus.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioResolverTest {
    private val resolver = ScenarioResolver()

    @Test
    fun `A launch resolves the requested package from discovered launchability`() {
        val scenario = scenario(goal("APP_LAUNCH", CapabilityScope.APP, SPOTIFY))

        val plan = resolver.resolve(scenario, listOf(app(SPOTIFY)), emptyList())

        assertEquals(ActionPlanStatus.NOT_VERIFIED, plan.status)
        assertEquals(Provider.App(SPOTIFY, "${SPOTIFY}_LAUNCH"), plan.steps.single().provider)
    }

    @Test
    fun `B launch then media next produces ordered app and system steps`() {
        val scenario = scenario(
            goal("APP_LAUNCH", CapabilityScope.APP, SPOTIFY),
            goal("MEDIA_NEXT", CapabilityScope.SYSTEM)
        )

        val plan = resolver.resolve(scenario, listOf(app(SPOTIFY)), emptyList())

        assertEquals(ActionPlanStatus.NOT_VERIFIED, plan.status)
        assertEquals(Provider.App(SPOTIFY, "${SPOTIFY}_LAUNCH"), plan.steps[0].provider)
        assertEquals(Provider.System("MEDIA_SESSION"), plan.steps[1].provider)
    }

    @Test
    fun `C navigation without target selects a verified registry provider and keeps destination`() {
        val goal = goal(
            capabilityName = "MAP_NAVIGATION",
            scope = CapabilityScope.APP,
            arguments = mapOf("destination" to "Çorlu")
        )
        val capability = capability(
            id = "com.maps_NAVIGATE",
            packageName = "com.maps",
            capabilityName = "MAP_NAVIGATION",
            provenance = ProvenanceStatus.TECHNICALLY_VERIFIED
        )

        val plan = resolver.resolve(scenario(goal), listOf(app("com.maps")), listOf(capability))

        assertEquals(ActionPlanStatus.RESOLVED, plan.status)
        assertEquals(Provider.App("com.maps", "com.maps_NAVIGATE"), plan.steps.single().provider)
        assertEquals("Çorlu", plan.steps.single().goal.arguments["destination"])
    }

    @Test
    fun `D volume down resolves to the system audio manager`() {
        val plan = resolver.resolve(
            scenario(goal("VOLUME_DOWN", CapabilityScope.SYSTEM)),
            emptyList(),
            emptyList()
        )

        assertEquals(ActionPlanStatus.RESOLVED, plan.status)
        assertEquals(Provider.System("AUDIO_MANAGER"), plan.steps.single().provider)
    }

    @Test
    fun `E media pause remains a system capability when a target package is supplied`() {
        val plan = resolver.resolve(
            scenario(goal("MEDIA_PAUSE", CapabilityScope.SYSTEM, "com.aimp.player")),
            emptyList(),
            emptyList()
        )

        assertEquals(ActionPlanStatus.RESOLVED, plan.status)
        assertEquals(Provider.System("MEDIA_SESSION"), plan.steps.single().provider)
        assertTrue(plan.steps.none { it.provider is Provider.App })
    }

    @Test
    fun `system catalog resolves every milestone capability to its Android service`() {
        val expectedServices = mapOf(
            "MEDIA_PLAY" to "MEDIA_SESSION",
            "MEDIA_PAUSE" to "MEDIA_SESSION",
            "MEDIA_NEXT" to "MEDIA_SESSION",
            "MEDIA_PREVIOUS" to "MEDIA_SESSION",
            "VOLUME_UP" to "AUDIO_MANAGER",
            "VOLUME_DOWN" to "AUDIO_MANAGER"
        )

        expectedServices.forEach { (capabilityName, expectedService) ->
            val plan = resolver.resolve(
                scenario(goal(capabilityName, CapabilityScope.SYSTEM)),
                emptyList(),
                emptyList()
            )

            assertEquals(capabilityName, ActionPlanStatus.RESOLVED, plan.status)
            assertEquals(capabilityName, Provider.System(expectedService), plan.steps.single().provider)
        }
    }

    @Test
    fun `F missing registry capability does not invent a provider`() {
        val plan = resolver.resolve(
            scenario(goal("UNKNOWN_APP_ACTION", CapabilityScope.APP)),
            listOf(app("com.example")),
            emptyList()
        )

        assertEquals(ActionPlanStatus.NEEDS_PROVIDER, plan.status)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun `target package is mandatory when supplied`() {
        val capability = capability(
            id = "com.other_MAP",
            packageName = "com.other",
            capabilityName = "MAP_NAVIGATION",
            provenance = ProvenanceStatus.TECHNICALLY_VERIFIED
        )

        val plan = resolver.resolve(
            scenario(goal("MAP_NAVIGATION", CapabilityScope.APP, "com.requested")),
            listOf(app("com.other"), app("com.requested")),
            listOf(capability)
        )

        assertEquals(ActionPlanStatus.NEEDS_PROVIDER, plan.status)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun `unverified registry provenance never produces a resolved plan`() {
        listOf(
            ProvenanceStatus.AI_SUGGESTED,
            ProvenanceStatus.HUMAN_CONFIRMED,
            ProvenanceStatus.TECHNICALLY_DISCOVERED
        ).forEach { provenance ->
            val capability = capability(
                id = "com.maps_MAP_$provenance",
                packageName = "com.maps",
                capabilityName = "MAP_NAVIGATION",
                provenance = provenance
            )

            val plan = resolver.resolve(
                scenario(goal("MAP_NAVIGATION", CapabilityScope.APP)),
                listOf(app("com.maps")),
                listOf(capability)
            )

            assertEquals(provenance.name, ActionPlanStatus.NOT_VERIFIED, plan.status)
            assertEquals(Provider.App("com.maps", capability.id), plan.steps.single().provider)
        }
    }

    @Test
    fun `G one verified provider without target resolves`() {
        val verified = capability(
            id = "com.maps_MAP",
            packageName = "com.maps",
            capabilityName = "MAP_NAVIGATION",
            provenance = ProvenanceStatus.TECHNICALLY_VERIFIED
        )

        val plan = resolver.resolve(
            scenario(goal("MAP_NAVIGATION", CapabilityScope.APP)),
            listOf(app("com.maps")),
            listOf(verified)
        )

        assertEquals(ActionPlanStatus.RESOLVED, plan.status)
        assertEquals(Provider.App("com.maps", "com.maps_MAP"), plan.steps.single().provider)
    }

    @Test
    fun `H two verified providers without target are ambiguous`() {
        val capabilities = listOf(
            capability(
                id = "com.maps.one_MAP",
                packageName = "com.maps.one",
                capabilityName = "MAP_NAVIGATION",
                provenance = ProvenanceStatus.TECHNICALLY_VERIFIED
            ),
            capability(
                id = "com.maps.two_MAP",
                packageName = "com.maps.two",
                capabilityName = "MAP_NAVIGATION",
                provenance = ProvenanceStatus.TECHNICALLY_VERIFIED
            )
        )

        val plan = resolver.resolve(
            scenario(goal("MAP_NAVIGATION", CapabilityScope.APP)),
            listOf(app("com.maps.one"), app("com.maps.two")),
            capabilities
        )

        assertEquals(ActionPlanStatus.AMBIGUOUS_PROVIDER, plan.status)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun `I no app provider without target needs provider`() {
        val plan = resolver.resolve(
            scenario(goal("MAP_NAVIGATION", CapabilityScope.APP)),
            listOf(app("com.maps")),
            emptyList()
        )

        assertEquals(ActionPlanStatus.NEEDS_PROVIDER, plan.status)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun `J blank capability makes scenario invalid without provider`() {
        val plan = resolver.resolve(
            scenario(goal("   ", CapabilityScope.SYSTEM)),
            emptyList(),
            emptyList()
        )

        assertEquals(ActionPlanStatus.INVALID_SCENARIO, plan.status)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun `K unknown system capability needs provider without invention`() {
        val plan = resolver.resolve(
            scenario(goal("UNKNOWN_SYSTEM_ACTION", CapabilityScope.SYSTEM)),
            emptyList(),
            emptyList()
        )

        assertEquals(ActionPlanStatus.NEEDS_PROVIDER, plan.status)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun `resolved plus not verified aggregates to not verified`() {
        val unverified = capability(
            id = "com.maps_MAP",
            packageName = "com.maps",
            capabilityName = "MAP_NAVIGATION",
            provenance = ProvenanceStatus.HUMAN_CONFIRMED
        )

        val plan = resolver.resolve(
            scenario(
                goal("VOLUME_DOWN", CapabilityScope.SYSTEM),
                goal("MAP_NAVIGATION", CapabilityScope.APP, "com.maps")
            ),
            listOf(app("com.maps")),
            listOf(unverified)
        )

        assertEquals(ActionPlanStatus.NOT_VERIFIED, plan.status)
    }

    @Test
    fun `not verified plus needs provider aggregates to needs provider`() {
        val unverified = capability(
            id = "com.maps_MAP",
            packageName = "com.maps",
            capabilityName = "MAP_NAVIGATION",
            provenance = ProvenanceStatus.AI_SUGGESTED
        )

        val plan = resolver.resolve(
            scenario(
                goal("MAP_NAVIGATION", CapabilityScope.APP, "com.maps"),
                goal("UNKNOWN_SYSTEM_ACTION", CapabilityScope.SYSTEM)
            ),
            listOf(app("com.maps")),
            listOf(unverified)
        )

        assertEquals(ActionPlanStatus.NEEDS_PROVIDER, plan.status)
    }

    @Test
    fun `needs provider plus ambiguity aggregates to ambiguity`() {
        val capabilities = listOf(
            capability(
                id = "com.maps.one_MAP",
                packageName = "com.maps.one",
                capabilityName = "MAP_NAVIGATION",
                provenance = ProvenanceStatus.TECHNICALLY_VERIFIED
            ),
            capability(
                id = "com.maps.two_MAP",
                packageName = "com.maps.two",
                capabilityName = "MAP_NAVIGATION",
                provenance = ProvenanceStatus.TECHNICALLY_VERIFIED
            )
        )

        val plan = resolver.resolve(
            scenario(
                goal("UNKNOWN_SYSTEM_ACTION", CapabilityScope.SYSTEM),
                goal("MAP_NAVIGATION", CapabilityScope.APP)
            ),
            listOf(app("com.maps.one"), app("com.maps.two")),
            capabilities
        )

        assertEquals(ActionPlanStatus.AMBIGUOUS_PROVIDER, plan.status)
    }

    @Test
    fun `ambiguity plus invalid goal aggregates to invalid scenario`() {
        val capabilities = listOf(
            capability(
                id = "com.maps.one_MAP",
                packageName = "com.maps.one",
                capabilityName = "MAP_NAVIGATION",
                provenance = ProvenanceStatus.TECHNICALLY_VERIFIED
            ),
            capability(
                id = "com.maps.two_MAP",
                packageName = "com.maps.two",
                capabilityName = "MAP_NAVIGATION",
                provenance = ProvenanceStatus.TECHNICALLY_VERIFIED
            )
        )

        val plan = resolver.resolve(
            scenario(
                goal("MAP_NAVIGATION", CapabilityScope.APP),
                goal("", CapabilityScope.SYSTEM)
            ),
            listOf(app("com.maps.one"), app("com.maps.two")),
            capabilities
        )

        assertEquals(ActionPlanStatus.INVALID_SCENARIO, plan.status)
    }

    private fun scenario(vararg goals: Goal) = Scenario("request", goals.toList())

    private fun goal(
        capabilityName: String,
        scope: CapabilityScope,
        targetPackageName: String? = null,
        arguments: Map<String, String> = emptyMap()
    ) = Goal(capabilityName, scope, targetPackageName, arguments)

    private fun app(
        packageName: String,
        reviewState: AppReviewState = AppReviewState.SELECT,
        isLaunchable: Boolean = true
    ) = AppRegistryItem(
        packageName = packageName,
        discoveredApp = DiscoveredApp(
            packageName = packageName,
            appLabel = packageName,
            versionName = "1",
            versionCode = 1,
            isSystemApp = false,
            isEnabled = true,
            isLaunchable = isLaunchable,
            launcherActivity = "MainActivity",
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

    private fun capability(
        id: String,
        packageName: String,
        capabilityName: String,
        provenance: ProvenanceStatus
    ) = CapabilityRegistryItem(
        id = id,
        capabilityName = capabilityName,
        packageName = packageName,
        appLabel = packageName,
        category = "TEST",
        provenance = provenance,
        confidence = 0.9,
        possibleAccessMethod = AccessMethod.UNKNOWN,
        appFeatures = ""
    )

    companion object {
        private const val SPOTIFY = "com.spotify.music"
    }
}
