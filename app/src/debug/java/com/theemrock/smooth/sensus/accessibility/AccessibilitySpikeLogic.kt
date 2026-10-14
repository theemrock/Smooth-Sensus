package com.theemrock.smooth.sensus.accessibility

internal data class SpikeNode(
    val index: Int,
    val parentIndex: Int?,
    val viewId: String?,
    val contentDescription: String?,
    val text: String?,
    val clickable: Boolean,
)

internal data class SpikeTarget(
    val viewIds: Set<String> = emptySet(),
    val contentDescriptions: Set<String> = emptySet(),
    val texts: Set<String> = emptySet(),
)

internal enum class MatchField {
    VIEW_ID,
    CONTENT_DESCRIPTION,
    TEXT,
}

internal data class SpikeSelection(
    val nodeIndex: Int,
    val clickNodeIndex: Int,
    val matchedBy: MatchField,
)

internal object AccessibilitySpikeLogic {
    fun select(nodes: List<SpikeNode>, target: SpikeTarget): SpikeSelection? {
        val byIndex = nodes.associateBy(SpikeNode::index)
        val match = findMatch(nodes, target.viewIds, SpikeNode::viewId)
            ?.let { it to MatchField.VIEW_ID }
            ?: findMatch(nodes, target.contentDescriptions, SpikeNode::contentDescription)
                ?.let { it to MatchField.CONTENT_DESCRIPTION }
            ?: findMatch(nodes, target.texts, SpikeNode::text)
                ?.let { it to MatchField.TEXT }
            ?: return null

        var clickNode = match.first
        while (!clickNode.clickable) {
            clickNode = clickNode.parentIndex?.let(byIndex::get) ?: return null
        }
        return SpikeSelection(match.first.index, clickNode.index, match.second)
    }

    fun containsExpectedText(nodes: List<SpikeNode>, expectedTexts: Set<String>): Boolean =
        findMatch(nodes, expectedTexts, SpikeNode::text) != null

    private fun findMatch(
        nodes: List<SpikeNode>,
        expectedValues: Set<String>,
        value: (SpikeNode) -> String?,
    ): SpikeNode? {
        val normalizedExpected = expectedValues.mapTo(mutableSetOf(), ::normalize)
        return nodes.firstOrNull { node ->
            value(node)?.let(::normalize) in normalizedExpected
        }
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}
