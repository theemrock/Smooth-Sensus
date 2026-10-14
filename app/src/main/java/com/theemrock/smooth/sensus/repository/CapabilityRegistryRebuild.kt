package com.theemrock.smooth.sensus.repository

import com.theemrock.smooth.sensus.data.CapabilityRegistryEntity
import com.theemrock.smooth.sensus.model.ProvenanceStatus

internal fun preserveHumanConfirmedProvenance(
    existing: List<CapabilityRegistryEntity>,
    generated: List<CapabilityRegistryEntity>
): List<CapabilityRegistryEntity> {
    val humanConfirmedIds = existing.asSequence()
        .filter { it.provenanceStr == ProvenanceStatus.HUMAN_CONFIRMED.name }
        .map { it.id }
        .toHashSet()

    return generated.map { capability ->
        if (capability.id in humanConfirmedIds) {
            capability.copy(provenanceStr = ProvenanceStatus.HUMAN_CONFIRMED.name)
        } else {
            capability
        }
    }
}
