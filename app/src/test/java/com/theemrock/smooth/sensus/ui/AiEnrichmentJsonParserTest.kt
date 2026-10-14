package com.theemrock.smooth.sensus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiEnrichmentJsonParserTest {

    @Test
    fun `valid enrichment JSON preserves the existing schema`() {
        val json = """
            [{
              "packageName":"com.example.player",
              "category":"MEDIA",
              "role":"PLAYER",
              "capabilities":[{
                "capabilityName":"PLAY",
                "possibleSmoothAccessMethods":"MEDIA_SESSION",
                "confidence":0.9,
                "appFeatures":"Playback"
              }]
            }]
        """.trimIndent()

        val result = parseAiEnrichmentsOrNull(json)

        assertEquals("com.example.player", result?.single()?.packageName)
        assertEquals("PLAY", result?.single()?.capabilities?.single()?.capabilityName)
    }

    @Test
    fun `malformed enrichment JSON returns null without throwing`() {
        assertNull(parseAiEnrichmentsOrNull("not-json"))
    }
}
