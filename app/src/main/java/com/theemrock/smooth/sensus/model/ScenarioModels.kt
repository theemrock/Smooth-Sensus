package com.theemrock.smooth.sensus.model

data class Scenario(
    val originalRequest: String,
    val goals: List<Goal>
)

data class Goal(
    val capabilityName: String,
    val scope: CapabilityScope,
    val targetPackageName: String? = null,
    val arguments: Map<String, String> = emptyMap()
)

enum class CapabilityScope {
    APP,
    SYSTEM
}

sealed class Provider {
    data class App(
        val packageName: String,
        val capabilityRegistryId: String
    ) : Provider()

    data class System(
        val service: String
    ) : Provider()
}

data class ActionPlan(
    val steps: List<ActionStep>,
    val status: ActionPlanStatus
)

enum class ActionPlanStatus {
    RESOLVED,
    NEEDS_PROVIDER,
    NOT_VERIFIED,
    AMBIGUOUS_PROVIDER,
    INVALID_SCENARIO
}

data class ActionStep(
    val goal: Goal,
    val provider: Provider
)
