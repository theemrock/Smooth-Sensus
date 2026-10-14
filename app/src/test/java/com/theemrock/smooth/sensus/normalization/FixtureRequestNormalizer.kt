package com.theemrock.smooth.sensus.normalization

import com.theemrock.smooth.sensus.model.Goal
import com.theemrock.smooth.sensus.model.Scenario

class FixtureRequestNormalizer(
    private val fixtures: Map<String, List<Goal>>
) : RequestNormalizer {
    override fun normalize(request: String): NormalizationResult {
        if (request.isBlank()) {
            return NormalizationResult.Failure(NormalizationFailureReason.EMPTY_REQUEST)
        }

        val goals = fixtures[request]
            ?: return NormalizationResult.Failure(NormalizationFailureReason.NOT_UNDERSTOOD)
        return NormalizationResult.Success(Scenario(request, goals))
    }
}
