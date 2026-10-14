package com.theemrock.smooth.sensus.repository

import com.theemrock.smooth.sensus.data.AppReviewStateEntity
import com.theemrock.smooth.sensus.data.CapabilityRegistryEntity
import com.theemrock.smooth.sensus.model.ProvenanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CapabilityRegistryRebuildTest {
    @Test
    fun humanConfirmedAiCapabilityRemainsConfirmedAfterRebuild() {
        val id = "com.spotify.music_AI_PLAY_MUSIC"
        val existing = listOf(capability(id, ProvenanceStatus.HUMAN_CONFIRMED))
        val generated = listOf(capability(id, ProvenanceStatus.AI_SUGGESTED))

        val result = preserveHumanConfirmedProvenance(existing, generated)

        assertEquals(ProvenanceStatus.HUMAN_CONFIRMED.name, result.single().provenanceStr)
    }

    @Test
    fun newAiCapabilityRemainsAiSuggested() {
        val generated = listOf(
            capability("com.spotify.music_AI_NEW_CAPABILITY", ProvenanceStatus.AI_SUGGESTED)
        )

        val result = preserveHumanConfirmedProvenance(emptyList(), generated)

        assertEquals(ProvenanceStatus.AI_SUGGESTED.name, result.single().provenanceStr)
    }

    @Test
    fun removedHumanConfirmedCapabilityIsNotResurrected() {
        val removedId = "com.spotify.music_AI_REMOVED"
        val existing = listOf(capability(removedId, ProvenanceStatus.HUMAN_CONFIRMED))
        val generated = listOf(
            capability("com.spotify.music_AI_CURRENT", ProvenanceStatus.AI_SUGGESTED)
        )

        val result = preserveHumanConfirmedProvenance(existing, generated)

        assertFalse(result.any { it.id == removedId })
        assertEquals(listOf("com.spotify.music_AI_CURRENT"), result.map { it.id })
    }

    @Test
    fun packageReviewStatesAreUnaffectedByCapabilityPreservation() {
        val reviewStates = listOf(
            AppReviewStateEntity("com.spotify.music", "SELECT"),
            AppReviewStateEntity("com.example.unknown", "UNKNOWN")
        )
        val originalReviewStates = reviewStates.toList()

        preserveHumanConfirmedProvenance(
            existing = listOf(capability("com.spotify.music_AI_PLAY", ProvenanceStatus.HUMAN_CONFIRMED)),
            generated = listOf(capability("com.spotify.music_AI_PLAY", ProvenanceStatus.AI_SUGGESTED))
        )

        assertEquals(originalReviewStates, reviewStates)
    }

    private fun capability(id: String, provenance: ProvenanceStatus) = CapabilityRegistryEntity(
        id = id,
        capabilityName = id.substringAfterLast('_'),
        packageName = "com.spotify.music",
        appLabel = "Spotify",
        category = "MEDIA",
        provenanceStr = provenance.name,
        confidence = 0.9,
        possibleAccessMethodStr = "MEDIA_SESSION",
        appFeatures = "Playback"
    )
}
