package com.theemrock.smooth.sensus.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySpikeLogicTest {
    @Test
    fun selectPrefersViewIdOverDescriptionAndText() {
        val nodes = listOf(
            SpikeNode(0, null, null, null, "Yüklü uygulamalar", true),
            SpikeNode(1, null, null, "Yüklü uygulamalar", null, true),
            SpikeNode(2, null, "com.android.settings:id/installed_apps", null, null, true),
        )
        val target = SpikeTarget(
            viewIds = setOf("com.android.settings:id/installed_apps"),
            contentDescriptions = setOf("Yüklü uygulamalar"),
            texts = setOf("Yüklü uygulamalar"),
        )

        val selection = AccessibilitySpikeLogic.select(nodes, target)

        assertEquals(2, selection?.nodeIndex)
        assertEquals(2, selection?.clickNodeIndex)
        assertEquals(MatchField.VIEW_ID, selection?.matchedBy)
    }

    @Test
    fun selectUsesClickableAncestorForTextMatch() {
        val nodes = listOf(
            SpikeNode(0, null, null, null, null, true),
            SpikeNode(1, 0, null, null, "Yüklü uygulamalar", false),
        )

        val selection = AccessibilitySpikeLogic.select(
            nodes,
            SpikeTarget(texts = setOf("Yüklü uygulamalar")),
        )

        assertEquals(1, selection?.nodeIndex)
        assertEquals(0, selection?.clickNodeIndex)
        assertEquals(MatchField.TEXT, selection?.matchedBy)
    }

    @Test
    fun verifyExpectedTextIgnoresCaseAndOuterWhitespace() {
        val nodes = listOf(
            SpikeNode(0, null, null, null, "  smooth  ", false),
        )

        assertTrue(AccessibilitySpikeLogic.containsExpectedText(nodes, setOf("SMOOTH")))
    }
}
