package com.theemrock.smooth.sensus.normalization

import com.theemrock.smooth.sensus.model.CapabilityScope
import com.theemrock.smooth.sensus.model.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestNormalizerTest {
    private val spotifyRequest = "Spotify'ı aç"
    private val compoundRequest = "Spotify'ı aç, sonrakine geç"
    private val navigationRequest = "Beni Çorlu'ya götür"
    private val volumeRequest = "Sesi kıs"

    private val normalizer = FixtureRequestNormalizer(
        mapOf(
            spotifyRequest to listOf(
                Goal("APP_LAUNCH", CapabilityScope.APP, "com.spotify.music")
            ),
            compoundRequest to listOf(
                Goal("APP_LAUNCH", CapabilityScope.APP, "com.spotify.music"),
                Goal("MEDIA_NEXT", CapabilityScope.SYSTEM)
            ),
            navigationRequest to listOf(
                Goal(
                    capabilityName = "MAP_NAVIGATION",
                    scope = CapabilityScope.APP,
                    arguments = mapOf("destination" to "Çorlu")
                )
            ),
            volumeRequest to listOf(
                Goal("VOLUME_DOWN", CapabilityScope.SYSTEM)
            )
        )
    )

    @Test
    fun `A Spotify fixture preserves request and resolves package identity`() {
        val scenario = success(spotifyRequest)

        assertEquals(spotifyRequest, scenario.originalRequest)
        assertEquals(
            Goal("APP_LAUNCH", CapabilityScope.APP, "com.spotify.music"),
            scenario.goals.single()
        )
    }

    @Test
    fun `B compound fixture produces two goals`() {
        val scenario = success(compoundRequest)

        assertEquals(2, scenario.goals.size)
        assertEquals("com.spotify.music", scenario.goals[0].targetPackageName)
        assertEquals(null, scenario.goals[1].targetPackageName)
    }

    @Test
    fun `C navigation fixture preserves destination argument`() {
        val goal = success(navigationRequest).goals.single()

        assertEquals("MAP_NAVIGATION", goal.capabilityName)
        assertEquals(CapabilityScope.APP, goal.scope)
        assertEquals(null, goal.targetPackageName)
        assertEquals(mapOf("destination" to "Çorlu"), goal.arguments)
    }

    @Test
    fun `D volume fixture creates untargeted system goal`() {
        val goal = success(volumeRequest).goals.single()

        assertEquals("VOLUME_DOWN", goal.capabilityName)
        assertEquals(CapabilityScope.SYSTEM, goal.scope)
        assertEquals(null, goal.targetPackageName)
        assertTrue(goal.arguments.isEmpty())
    }

    @Test
    fun `E blank request returns empty request failure`() {
        assertEquals(
            NormalizationResult.Failure(NormalizationFailureReason.EMPTY_REQUEST),
            normalizer.normalize("   ")
        )
    }

    @Test
    fun `F missing fixture returns not understood without fallback goal`() {
        assertEquals(
            NormalizationResult.Failure(NormalizationFailureReason.NOT_UNDERSTOOD),
            normalizer.normalize("Bilinmeyen istek")
        )
    }

    @Test
    fun `G arguments remain a generic string map`() {
        val arguments: Map<String, String> = success(navigationRequest).goals.single().arguments

        assertEquals("Çorlu", arguments["destination"])
    }

    @Test
    fun `H compound goal order follows input order`() {
        assertEquals(
            listOf("APP_LAUNCH", "MEDIA_NEXT"),
            success(compoundRequest).goals.map { it.capabilityName }
        )
    }

    @Test
    fun `invalid external output has a dedicated failure reason`() {
        val failure = NormalizationResult.Failure(NormalizationFailureReason.INVALID_OUTPUT)

        assertEquals(NormalizationFailureReason.INVALID_OUTPUT, failure.reason)
    }

    private fun success(request: String) =
        (normalizer.normalize(request) as NormalizationResult.Success).scenario
}
