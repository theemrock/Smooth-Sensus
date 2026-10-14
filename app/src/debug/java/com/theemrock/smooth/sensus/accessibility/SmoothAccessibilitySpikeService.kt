package com.theemrock.smooth.sensus.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SmoothAccessibilitySpikeService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        Log.i(SPIKE_TAG, "SERVICE {\"state\":\"CONNECTED\"}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (running || !isArmed()) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != SETTINGS_PACKAGE) return

        val tree = readTree(root)
        logRead("before", root, tree.nodes)
        val selection = AccessibilitySpikeLogic.select(tree.nodes, TARGET)
        if (selection == null) {
            Log.i(SPIKE_TAG, "FIND {\"result\":\"FAIL\",\"boundary\":\"node_discoverability\"}")
            return
        }

        val clickNode = tree.platformNodes.getValue(selection.clickNodeIndex)
        Log.i(
            SPIKE_TAG,
            "FIND {\"result\":\"PASS\",\"matchedBy\":\"${selection.matchedBy}\"," +
                "\"node\":${selection.nodeIndex},\"clickNode\":${selection.clickNodeIndex}}",
        )
        running = true
        disarm()
        val clicked = clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(SPIKE_TAG, "CLICK {\"result\":\"${if (clicked) "PASS" else "FAIL"}\"}")
        if (!clicked) {
            Log.i(SPIKE_TAG, "VERIFY {\"result\":\"FAIL\",\"boundary\":\"clickability\"}")
            running = false
            return
        }

        handler.postDelayed(::readAndVerifyAfterClick, VERIFY_DELAY_MS)
    }

    override fun onInterrupt() {
        Log.i(SPIKE_TAG, "SERVICE {\"state\":\"INTERRUPTED\"}")
    }

    private fun readAndVerifyAfterClick() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.i(SPIKE_TAG, "READ {\"phase\":\"after\",\"result\":\"FAIL\",\"boundary\":\"root_visibility\"}")
            Log.i(SPIKE_TAG, "VERIFY {\"result\":\"FAIL\",\"boundary\":\"root_visibility\"}")
            running = false
            return
        }

        val tree = readTree(root)
        logRead("after", root, tree.nodes)
        val packageMatches = root.packageName?.toString() == SETTINGS_PACKAGE
        val expectedState = AccessibilitySpikeLogic.containsExpectedText(tree.nodes, EXPECTED_TEXTS)
        val passed = packageMatches && expectedState
        Log.i(
            SPIKE_TAG,
            "VERIFY {\"result\":\"${if (passed) "PASS" else "FAIL"}\"," +
                "\"packageMatches\":$packageMatches,\"expectedText\":$expectedState}",
        )
        running = false
    }

    private fun readTree(root: AccessibilityNodeInfo): ObservedTree {
        val nodes = mutableListOf<SpikeNode>()
        val platformNodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int?>>()
        queue.add(root to null)

        while (queue.isNotEmpty() && nodes.size < MAX_TREE_NODES) {
            val (node, parentIndex) = queue.removeFirst()
            val index = nodes.size
            nodes += SpikeNode(
                index = index,
                parentIndex = parentIndex,
                viewId = node.viewIdResourceName,
                contentDescription = node.contentDescription?.toString(),
                text = node.text?.toString(),
                clickable = node.isClickable,
            )
            platformNodes[index] = node
            for (childIndex in 0 until node.childCount) {
                node.getChild(childIndex)?.let { queue.add(it to index) }
            }
        }
        return ObservedTree(nodes, platformNodes)
    }

    private fun logRead(phase: String, root: AccessibilityNodeInfo, nodes: List<SpikeNode>) {
        val compactTree = nodes.asSequence()
            .filter { it.viewId != null || it.contentDescription != null || it.text != null || it.clickable }
            .take(MAX_LOGGED_NODES)
            .joinToString(separator = ",", prefix = "[", postfix = "]") { node ->
                "{\"i\":${node.index},\"p\":${node.parentIndex ?: -1}," +
                    "\"id\":${json(node.viewId)},\"desc\":${json(node.contentDescription)}," +
                    "\"text\":${json(node.text)},\"click\":${node.clickable}}"
            }
        Log.i(
            SPIKE_TAG,
            "READ {\"phase\":\"$phase\",\"package\":${json(root.packageName?.toString())}," +
                "\"nodeCount\":${nodes.size},\"nodes\":$compactTree}",
        )
    }

    private fun isArmed(): Boolean =
        getSharedPreferences(SPIKE_PREFS, MODE_PRIVATE).getBoolean(SPIKE_ARMED, false)

    private fun disarm() {
        getSharedPreferences(SPIKE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(SPIKE_ARMED, false)
            .apply()
    }

    private fun json(value: String?): String = value
        ?.replace("\\", "\\\\")
        ?.replace("\"", "\\\"")
        ?.replace("\n", "\\n")
        ?.let { "\"$it\"" }
        ?: "null"

    private data class ObservedTree(
        val nodes: List<SpikeNode>,
        val platformNodes: Map<Int, AccessibilityNodeInfo>,
    )

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val MAX_TREE_NODES = 250
        const val MAX_LOGGED_NODES = 24
        const val VERIFY_DELAY_MS = 1_200L

        val TARGET = SpikeTarget(
            contentDescriptions = setOf("Yüklü uygulamalar", "Installed apps"),
            texts = setOf("Yüklü uygulamalar", "Installed apps"),
        )
        val EXPECTED_TEXTS = setOf("Smooth Dialogue Accessibility Spike")
    }
}
