package com.ezhou.sttbutton

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object MicLocator {
    const val GBOARD = "com.google.android.inputmethod.latin"

    fun isVoiceKey(description: CharSequence?): Boolean =
        description != null && description.contains("voice", ignoreCase = true)

    /** Screen bounds of Gboard's voice-typing key, or null when the tree exposes none. */
    fun find(root: AccessibilityNodeInfo): Rect? {
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.packageName == GBOARD && isVoiceKey(node.contentDescription)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bounds.isEmpty) return bounds
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    fun descriptions(root: AccessibilityNodeInfo): List<String> {
        val found = mutableListOf<String>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.contentDescription?.let { found += it.toString() }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return found
    }
}
