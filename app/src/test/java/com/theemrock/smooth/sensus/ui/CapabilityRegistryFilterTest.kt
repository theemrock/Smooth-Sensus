package com.theemrock.smooth.sensus.ui

import com.theemrock.smooth.sensus.model.AccessMethod
import com.theemrock.smooth.sensus.model.CapabilityRegistryItem
import com.theemrock.smooth.sensus.model.ProvenanceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityRegistryFilterTest {
    @Test
    fun aiSuggestedFilterAndCaseInsensitiveSearchWorkTogether() {
        val capabilities = listOf(
            capability("aimp-ai", "AIMP", "com.aimp.player", "PLAY_AUDIO", "MEDIA", ProvenanceStatus.AI_SUGGESTED),
            capability("aimp-tech", "AIMP", "com.aimp.player", "LAUNCHABLE_ACTIVITY", "SYSTEM_LAUNCHER", ProvenanceStatus.TECHNICALLY_DISCOVERED),
            capability("spotify-ai", "Spotify", "com.spotify.music", "PLAY_AUDIO", "MEDIA", ProvenanceStatus.AI_SUGGESTED)
        )

        val result = filterCapabilityRegistry(
            capabilities = capabilities,
            query = "aImP",
            provenanceFilter = CapabilityProvenanceFilter.AI_SUGGESTED
        )

        assertEquals(listOf("aimp-ai"), result.map { it.id })
    }

    @Test
    fun searchMatchesPackageCapabilityAndCategory() {
        val item = capability(
            "maps-ai",
            "Maps",
            "com.example.navigator",
            "START_NAVIGATION",
            "NAVIGATION",
            ProvenanceStatus.AI_SUGGESTED
        )

        listOf("example.navigator", "start_navigation", "navigation").forEach { query ->
            assertEquals(
                listOf("maps-ai"),
                filterCapabilityRegistry(
                    capabilities = listOf(item),
                    query = query,
                    provenanceFilter = CapabilityProvenanceFilter.ALL
                ).map { it.id }
            )
        }
    }

    private fun capability(
        id: String,
        appLabel: String,
        packageName: String,
        capabilityName: String,
        category: String,
        provenance: ProvenanceStatus
    ) = CapabilityRegistryItem(
        id = id,
        capabilityName = capabilityName,
        packageName = packageName,
        appLabel = appLabel,
        category = category,
        provenance = provenance,
        confidence = 1.0,
        possibleAccessMethod = AccessMethod.UNKNOWN,
        appFeatures = ""
    )
}
