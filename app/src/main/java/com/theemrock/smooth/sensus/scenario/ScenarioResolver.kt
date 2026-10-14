package com.theemrock.smooth.sensus.scenario

import com.theemrock.smooth.sensus.model.ActionPlan
import com.theemrock.smooth.sensus.model.ActionPlanStatus
import com.theemrock.smooth.sensus.model.ActionStep
import com.theemrock.smooth.sensus.model.AppRegistryItem
import com.theemrock.smooth.sensus.model.AppReviewState
import com.theemrock.smooth.sensus.model.CapabilityRegistryItem
import com.theemrock.smooth.sensus.model.CapabilityScope
import com.theemrock.smooth.sensus.model.Goal
import com.theemrock.smooth.sensus.model.Provider
import com.theemrock.smooth.sensus.model.ProvenanceStatus
import com.theemrock.smooth.sensus.model.Scenario

class ScenarioResolver {
    fun resolve(
        scenario: Scenario,
        apps: List<AppRegistryItem>,
        capabilities: List<CapabilityRegistryItem>
    ): ActionPlan {
        val resolvedGoals = scenario.goals.map { goal ->
            if (goal.capabilityName.isBlank()) {
                GoalResolution(null, ActionPlanStatus.INVALID_SCENARIO)
            } else {
                when (goal.scope) {
                    CapabilityScope.APP -> resolveAppGoal(goal, apps, capabilities)
                    CapabilityScope.SYSTEM -> resolveSystemGoal(goal)
                }
            }
        }

        return ActionPlan(
            steps = resolvedGoals.mapNotNull { it.step },
            status = aggregateStatus(resolvedGoals.map { it.status })
        )
    }

    private fun resolveSystemGoal(goal: Goal): GoalResolution {
        val service = systemCapabilities[goal.capabilityName]
            ?: return GoalResolution(null, ActionPlanStatus.NEEDS_PROVIDER)
        return GoalResolution(
            step = ActionStep(goal, Provider.System(service)),
            status = ActionPlanStatus.RESOLVED
        )
    }

    private fun resolveAppGoal(
        goal: Goal,
        apps: List<AppRegistryItem>,
        capabilities: List<CapabilityRegistryItem>
    ): GoalResolution {
        if (goal.capabilityName == APP_LAUNCH) {
            return resolveLaunch(goal, apps, capabilities)
        }

        val appsByPackage = apps.associateBy { it.packageName }
        val candidates = capabilities.asSequence()
            .filter { it.capabilityName == goal.capabilityName }
            .filter { goal.targetPackageName == null || it.packageName == goal.targetPackageName }
            .filter { capability ->
                appsByPackage[capability.packageName]?.let { app ->
                    app.discoveredApp.isEnabled && app.reviewState != AppReviewState.IGNORE
                } == true
            }
            .toList()

        if (candidates.isEmpty()) {
            return GoalResolution(null, ActionPlanStatus.NEEDS_PROVIDER)
        }

        val verifiedCandidates = candidates.filter {
            it.provenance == ProvenanceStatus.TECHNICALLY_VERIFIED
        }
        if (verifiedCandidates.size > 1 ||
            (verifiedCandidates.isEmpty() && candidates.size > 1)
        ) {
            return GoalResolution(null, ActionPlanStatus.AMBIGUOUS_PROVIDER)
        }

        val candidate = verifiedCandidates.singleOrNull() ?: candidates.single()
        return GoalResolution(
            step = ActionStep(goal, Provider.App(candidate.packageName, candidate.id)),
            status = verificationStatus(candidate.provenance)
        )
    }

    private fun resolveLaunch(
        goal: Goal,
        apps: List<AppRegistryItem>,
        capabilities: List<CapabilityRegistryItem>
    ): GoalResolution {
        val candidates = apps.asSequence()
            .filter { goal.targetPackageName == null || it.packageName == goal.targetPackageName }
            .filter {
                it.discoveredApp.isEnabled &&
                    it.discoveredApp.isLaunchable &&
                    it.reviewState != AppReviewState.IGNORE
            }
            .map { app ->
                val capabilityId = "${app.packageName}_LAUNCH"
                LaunchCandidate(
                    app = app,
                    capabilityId = capabilityId,
                    provenance = capabilities.firstOrNull { it.id == capabilityId }?.provenance
                )
            }
            .toList()

        if (candidates.isEmpty()) {
            return GoalResolution(null, ActionPlanStatus.NEEDS_PROVIDER)
        }

        val verifiedCandidates = candidates.filter {
            it.provenance == ProvenanceStatus.TECHNICALLY_VERIFIED
        }
        if (verifiedCandidates.size > 1 ||
            (verifiedCandidates.isEmpty() && candidates.size > 1)
        ) {
            return GoalResolution(null, ActionPlanStatus.AMBIGUOUS_PROVIDER)
        }

        val candidate = verifiedCandidates.singleOrNull() ?: candidates.single()
        return GoalResolution(
            step = ActionStep(
                goal,
                Provider.App(candidate.app.packageName, candidate.capabilityId)
            ),
            status = if (candidate.provenance == ProvenanceStatus.TECHNICALLY_VERIFIED) {
                ActionPlanStatus.RESOLVED
            } else {
                ActionPlanStatus.NOT_VERIFIED
            }
        )
    }

    private fun verificationStatus(provenance: ProvenanceStatus): ActionPlanStatus =
        if (provenance == ProvenanceStatus.TECHNICALLY_VERIFIED) {
            ActionPlanStatus.RESOLVED
        } else {
            ActionPlanStatus.NOT_VERIFIED
        }

    private fun aggregateStatus(statuses: List<ActionPlanStatus>): ActionPlanStatus =
        statuses.maxByOrNull { statusPriority.getValue(it) } ?: ActionPlanStatus.RESOLVED

    private data class GoalResolution(
        val step: ActionStep?,
        val status: ActionPlanStatus
    )

    private data class LaunchCandidate(
        val app: AppRegistryItem,
        val capabilityId: String,
        val provenance: ProvenanceStatus?
    )

    private companion object {
        const val APP_LAUNCH = "APP_LAUNCH"

        val systemCapabilities = mapOf(
            "MEDIA_PLAY" to "MEDIA_SESSION",
            "MEDIA_PAUSE" to "MEDIA_SESSION",
            "MEDIA_NEXT" to "MEDIA_SESSION",
            "MEDIA_PREVIOUS" to "MEDIA_SESSION",
            "VOLUME_UP" to "AUDIO_MANAGER",
            "VOLUME_DOWN" to "AUDIO_MANAGER"
        )

        val statusPriority = mapOf(
            ActionPlanStatus.RESOLVED to 0,
            ActionPlanStatus.NOT_VERIFIED to 1,
            ActionPlanStatus.NEEDS_PROVIDER to 2,
            ActionPlanStatus.AMBIGUOUS_PROVIDER to 3,
            ActionPlanStatus.INVALID_SCENARIO to 4
        )
    }
}
