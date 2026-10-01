package com.ezhou.sttbutton

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/** Puts a take's text at the caret of the focused field by the design's three paths, in order, each tried once. */
class Inserter(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())

    class Outcome(val path: String, val textBefore: String, val landed: Boolean)

    fun insert(field: AccessibilityNodeInfo, text: String): Outcome {
        val before = textOf(field)
        val path = when {
            viaInputConnection(field, text) -> "commit_text"
            viaClipboard(field, text) -> "clipboard"
            else -> {
                viaSetText(field, text, before)
                "set_text"
            }
        }
        val landed = awaitLanded(field, text)
        if (!landed) Log.w(DictationService.TAG, "insert via $path: read-back does not contain the text")
        return Outcome(path, before, landed)
    }

    fun textOf(field: AccessibilityNodeInfo): String =
        if (field.isShowingHintText) "" else field.text?.toString().orEmpty()

    private fun collapseSelection(field: AccessibilityNodeInfo) {
        val end = field.textSelectionEnd
        if (end >= 0 && field.textSelectionStart != end) setSelection(field, end, end)
    }

    private fun setSelection(field: AccessibilityNodeInfo, start: Int, end: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun viaInputConnection(field: AccessibilityNodeInfo, text: String): Boolean {
        val connection = service.inputMethod?.currentInputConnection ?: return false
        return try {
            collapseSelection(field)
            connection.commitText(text, 1, null)
            true
        } catch (e: Exception) {
            Log.w(DictationService.TAG, "commitText failed", e)
            false
        }
    }

    private fun viaClipboard(field: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        val saved: ClipData? = try {
            clipboard.primaryClip
        } catch (e: SecurityException) {
            null
        }
        // A clip that exists but cannot be read would be lost by the restore, so the path is skipped.
        if (saved == null && clipboard.hasPrimaryClip()) return false
        collapseSelection(field)
        val clip = ClipData.newPlainText("dictation", text).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        val pasted = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        handler.postDelayed({
            if (saved != null) clipboard.setPrimaryClip(saved) else clipboard.clearPrimaryClip()
        }, CLIP_RESTORE_MS)
        return pasted
    }

    private fun viaSetText(field: AccessibilityNodeInfo, text: String, before: String) {
        val caret = field.textSelectionEnd.takeIf { it in 0..before.length } ?: before.length
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                before.substring(0, caret) + text + before.substring(caret))
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        setSelection(field, caret + text.length, caret + text.length)
    }

    private fun awaitLanded(field: AccessibilityNodeInfo, text: String): Boolean {
        val needle = text.trim()
        repeat(READBACK_TRIES) {
            field.refresh()
            if (textOf(field).contains(needle)) return true
            Thread.sleep(READBACK_WAIT_MS)
        }
        return false
    }

    companion object {
        private const val CLIP_RESTORE_MS = 500L
        private const val READBACK_TRIES = 6
        private const val READBACK_WAIT_MS = 50L
    }
}
