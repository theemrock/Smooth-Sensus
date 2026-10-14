package com.theemrock.smooth.sensus.normalization

import com.theemrock.smooth.sensus.model.Scenario

interface RequestNormalizer {
    fun normalize(request: String): NormalizationResult
}

sealed class NormalizationResult {
    data class Success(
        val scenario: Scenario
    ) : NormalizationResult()

    data class Failure(
        val reason: NormalizationFailureReason
    ) : NormalizationResult()
}

enum class NormalizationFailureReason {
    EMPTY_REQUEST,
    NOT_UNDERSTOOD,
    INVALID_OUTPUT
}
