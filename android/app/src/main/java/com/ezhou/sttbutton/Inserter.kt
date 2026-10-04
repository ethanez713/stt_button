package com.ezhou.sttbutton

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Puts a segment at the caret. The input connection is the same channel the editor gives the
 * keyboard, so the caret is read and moved there. A commit that does not change the field is
 * not success: paste, then set-text, are tried when that is safe. The clipboard is written only
 * when the field is still unchanged afterwards, so a failed insert can be pasted by hand and a
 * successful one does not raise the system's copy toast.
 */
class Inserter(private val service: AccessibilityService) {
    class Outcome(
        val path: String, val textBefore: String, val landed: Boolean, val readback: String,
        val attempts: String, val copied: Boolean, val restoredCaret: Int,
    )

    data class CaretRead(val caret: Caret, val fromConnection: Boolean)

    /**
     * With [at], the text goes in at that offset instead of at the caret, and the caret is then put
     * back at [restore], moved along by the inserted text when it lay after the insertion point.
     */
    fun insert(
        field: AccessibilityNodeInfo, text: String, at: Int? = null, restore: Int? = null, useConnection: Boolean = true,
    ): Outcome {
        // Once focus has left this field the connection belongs to wherever it went, so it must not be written.
        val conn = if (useConnection) connection() else null
        val beforeNode = nodeView(field)
        val beforeAround = readAround(conn)
        val tried = mutableListOf<String>()
        var staged = false
        var commitMissing = conn == null
        if (!commitMissing) {
            tried += "commit_text"
            commitMissing = !commit(conn, field, text, at)
        }
        var verdict = confirm(conn, field, beforeNode, beforeAround.beforeCaret, text)
        while (true) {
            val next = InsertReadback.nextPath(
                verdict, commitMissing, InsertReadback.allowSetText(beforeNode, beforeAround.beforeCaret),
                triedPaste = "paste" in tried, triedSetText = "set_text" in tried,
            ) ?: break
            if (next == "paste" && !staged && !stageForPaste(text)) {
                tried += "paste"
                Log.w(DictationService.TAG, "paste skipped: the dictation is not on the clipboard")
                continue
            }
            if (next == "paste") staged = true
            tried += next
            when (next) {
                "paste" -> paste(conn, field, at)
                "set_text" -> setText(conn, field, text, beforeNode.text, at)
            }
            verdict = confirm(conn, field, beforeNode, beforeAround.beforeCaret, text)
        }
        val restoredCaret = if (at != null && restore != null && restore >= 0) {
            val caret = CaretWatch.restored(restore, at, text.length)
            select(conn, field, caret)
            caret
        } else {
            -1
        }
        val copied = verdict == InsertReadback.Verdict.UNCHANGED && setClip(ClipData.newPlainText("dictation", text))
        if (staged && !copied) clearClip()
        val path = tried.lastOrNull() ?: "none"
        val readback = verdict.name.lowercase()
        Log.i(DictationService.TAG, "insert readback=$readback attempts=${tried.joinToString(",")} copied=$copied")
        return Outcome(path, beforeNode.text, verdict == InsertReadback.Verdict.LANDED, readback,
            tried.joinToString(","), copied, restoredCaret)
    }

    fun readCaret(field: AccessibilityNodeInfo?): CaretRead {
        val around = readAround(connection())
        if (around.caret.known) return CaretRead(around.caret, true)
        if (field == null || !field.refresh()) return CaretRead(Caret.UNKNOWN, false)
        val start = field.textSelectionStart
        val end = field.textSelectionEnd
        if (start < 0 || end < 0) return CaretRead(Caret.UNKNOWN, false)
        return CaretRead(Caret(start, end), false)
    }

    fun textOf(field: AccessibilityNodeInfo): String =
        if (field.isShowingHintText) "" else field.text?.toString().orEmpty()

    private fun confirm(
        conn: InputMethod.AccessibilityInputConnection?, field: AccessibilityNodeInfo,
        beforeNode: InsertReadback.View, beforeCaret: InsertReadback.View, needle: String,
    ): InsertReadback.Verdict {
        var verdict = InsertReadback.Verdict.BLIND
        for (wait in READ_WAITS_MS) {
            if (wait > 0) Thread.sleep(wait.toLong())
            val node = nodeView(field)
            val caret = readAround(conn).beforeCaret
            verdict = InsertReadback.combine(
                InsertReadback.judge(beforeNode, node, needle),
                InsertReadback.judge(beforeCaret, caret, needle),
            )
            if (verdict == InsertReadback.Verdict.LANDED || verdict == InsertReadback.Verdict.DIVERGED) return verdict
        }
        return verdict
    }

    /**
     * Paste reads whatever is on the clipboard. A sensitive clip does not raise the system copy
     * toast and is kept out of clipboard history; the history copy happens only after a failed insert.
     */
    private fun stageForPaste(text: String): Boolean {
        val clip = ClipData.newPlainText("dictation", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        return setClip(clip)
    }

    private fun setClip(clip: ClipData): Boolean = try {
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    } catch (e: Exception) {
        Log.w(DictationService.TAG, "clipboard set failed", e)
        false
    }

    private fun clearClip() {
        try {
            service.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        } catch (e: Exception) {
            Log.w(DictationService.TAG, "clipboard clear failed", e)
        }
    }

    private fun commit(
        conn: InputMethod.AccessibilityInputConnection?, field: AccessibilityNodeInfo, text: String, at: Int?,
    ): Boolean {
        if (conn == null) return false
        return try {
            placeCaret(conn, field, at)
            conn.commitText(text, 1, null)
            true
        } catch (e: Exception) {
            Log.w(DictationService.TAG, "commitText failed", e)
            false
        }
    }

    private fun paste(conn: InputMethod.AccessibilityInputConnection?, field: AccessibilityNodeInfo, at: Int?) {
        placeCaret(conn, field, at)
        field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun setText(
        conn: InputMethod.AccessibilityInputConnection?, field: AccessibilityNodeInfo, text: String, before: String, at: Int?,
    ) {
        val caret = (at ?: field.textSelectionEnd).coerceIn(0, before.length)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                before.substring(0, caret) + text + before.substring(caret),
            )
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        select(conn, field, caret + text.length)
    }

    private fun placeCaret(conn: InputMethod.AccessibilityInputConnection?, field: AccessibilityNodeInfo, at: Int?) {
        if (at != null && at >= 0) {
            select(conn, field, at)
            return
        }
        val caret = readAround(conn).caret
        if (caret.known && !caret.collapsed) select(conn, field, caret.end)
        else collapseSelection(conn, field)
    }

    private fun select(conn: InputMethod.AccessibilityInputConnection?, field: AccessibilityNodeInfo, offset: Int) {
        if (offset < 0) return
        try {
            conn?.setSelection(offset, offset)
        } catch (e: Exception) {
            Log.w(DictationService.TAG, "setSelection failed", e)
        }
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, offset)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, offset)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun collapseSelection(conn: InputMethod.AccessibilityInputConnection?, field: AccessibilityNodeInfo) {
        val end = field.textSelectionEnd
        if (end >= 0 && field.textSelectionStart != end) select(conn, field, end)
    }

    private fun nodeView(field: AccessibilityNodeInfo): InsertReadback.View {
        if (!field.refresh()) return InsertReadback.View("", false)
        if (field.isShowingHintText) return InsertReadback.View("", true)
        val raw = field.text ?: return InsertReadback.View("", false)
        return InsertReadback.View(raw.toString(), true)
    }

    private data class Around(val beforeCaret: InsertReadback.View, val caret: Caret)

    private fun readAround(conn: InputMethod.AccessibilityInputConnection?): Around {
        if (conn == null) return Around(InsertReadback.View("", false), Caret.UNKNOWN)
        val surrounding = try {
            conn.getSurroundingText(SURROUND_BEFORE, SURROUND_AFTER, 0)
        } catch (e: Exception) {
            Log.w(DictationService.TAG, "getSurroundingText failed", e)
            null
        } ?: return Around(InsertReadback.View("", false), Caret.UNKNOWN)
        val text = surrounding.text?.toString().orEmpty()
        val caret = CaretWatch.absolute(surrounding.offset, surrounding.selectionStart, surrounding.selectionEnd)
        val before = if (surrounding.selectionStart in 0..text.length) text.substring(0, surrounding.selectionStart) else text
        return Around(InsertReadback.View(before, true), caret)
    }

    private fun connection() = service.inputMethod?.currentInputConnection

    companion object {
        private val READ_WAITS_MS = intArrayOf(0, 40, 80, 160)
        private const val SURROUND_BEFORE = 4000
        private const val SURROUND_AFTER = 200
    }
}
