package com.theemrock.smooth.sensus.ui

import com.theemrock.smooth.sensus.model.CapabilityRegistryItem
import com.theemrock.smooth.sensus.model.ProvenanceStatus

enum class CapabilityProvenanceFilter(
    val label: String,
    val provenance: ProvenanceStatus?
) {
    ALL("ALL", null),
    TECHNICAL("TECHNICAL", ProvenanceStatus.TECHNICALLY_DISCOVERED),
    AI_SUGGESTED("AI SUGGESTED", ProvenanceStatus.AI_SUGGESTED),
    HUMAN_CONFIRMED("HUMAN CONFIRMED", ProvenanceStatus.HUMAN_CONFIRMED),
    VERIFIED("VERIFIED", ProvenanceStatus.TECHNICALLY_VERIFIED),
    UNKNOWN("UNKNOWN", ProvenanceStatus.UNKNOWN)
}

fun filterCapabilityRegistry(
    capabilities: List<CapabilityRegistryItem>,
    query: String,
    provenanceFilter: CapabilityProvenanceFilter
): List<CapabilityRegistryItem> {
    val normalizedQuery = query.trim()
    return capabilities.filter { item ->
        val matchesQuery = normalizedQuery.isEmpty() ||
            item.appLabel.contains(normalizedQuery, ignoreCase = true) ||
            item.packageName.contains(normalizedQuery, ignoreCase = true) ||
            item.capabilityName.contains(normalizedQuery, ignoreCase = true) ||
            item.category.contains(normalizedQuery, ignoreCase = true)
        val matchesProvenance = provenanceFilter.provenance == null ||
            item.provenance == provenanceFilter.provenance
        matchesQuery && matchesProvenance
    }
}
