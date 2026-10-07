package com.ezhou.sttbutton

import android.accessibilityservice.InputMethod
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Rewrites the focused field's text through the accessibility service's input connection, as dictation
 * would while the user types, and writes one line per write to files/selftest/probe.jsonl. The modes
 * are `read`, `replace` (from, to, text), `typing` and `append` (plan 23). No field text is logged.
 */
class FieldProbe(private val service: DictationService, private val out: File) {
    private val main = Handler(Looper.getMainLooper())

    private class Seen(val text: String, val caret: Int, val fieldRead: String)

    fun run(mode: String, from: Int, to: Int, replacement: String) {
        when (mode) {
            "read" -> onMain { write(mode, read(), read(), null, null, 0L, 0) }
            "replace" -> onMain { replace(mode, from, to, replacement) }
            "typing" -> repeat(WRITES) { n -> onMain { typing(mode) }; if (n < WRITES - 1) Thread.sleep(GAP_MS) }
            "append" -> repeat(WRITES) { n -> onMain { append(mode, n + 1) }; if (n < WRITES - 1) Thread.sleep(GAP_MS) }
            else -> error("unknown probe mode $mode")
        }
    }

    private fun typing(mode: String) {
        val text = read().text
        val word = Regex("\\S+").find(text) ?: return write(mode, read(), read(), null, null, 0L, 0)
        val swapped = word.value.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
        replace(mode, word.range.first, word.range.last + 1, swapped)
    }

    private fun append(mode: String, n: Int) {
        val end = read().text.length
        replace(mode, end, end, " x$n")
    }

    private fun replace(mode: String, from: Int, to: Int, replacement: String) {
        val conn = connection() ?: return write(mode, Seen("", -1, "none"), Seen("", -1, "none"), null, null, 0L, 0)
        val before = read()
        val selectionsBefore = selections()
        val started = System.nanoTime()
        if (before.fieldRead != "full" || to > before.text.length) {
            return write(mode, before, read(), null, null, 0L, 0)
        }
        conn.setSelection(from, to)
        conn.commitText(replacement, 1, null)
        val (expectedText, expectedCaret) = ProbeCheck.expected(before.text, before.caret, from, to, replacement)
        conn.setSelection(expectedCaret, expectedCaret)
        val ms = (System.nanoTime() - started) / 1_000_000
        Thread.sleep(SETTLE_MS)
        write(mode, before, read(), expectedText, expectedCaret, ms, selections() - selectionsBefore)
    }

    private fun write(mode: String, before: Seen, after: Seen, expectedText: String?, expectedCaret: Int?, ms: Long, selections: Int) {
        val ok = if (mode == "read") before.fieldRead == "full" else expectedText != null && after.text == expectedText && after.caret == expectedCaret
        val line = JSONObject().put("mode", mode).put("pkg", service.focusedField()?.packageName?.toString() ?: JSONObject.NULL)
            .put("field_read", before.fieldRead).put("len_before", before.text.length).put("len_after", after.text.length)
            .put("caret_before", before.caret).put("caret_after", after.caret).put("caret_expected", expectedCaret ?: JSONObject.NULL)
            .put("ok", ok).put("first_diff", expectedText?.let { ProbeCheck.firstDiff(after.text, it) } ?: JSONObject.NULL)
            .put("ms", ms).put("selections", selections)
        out.appendText(line.toString() + "\n")
    }

    private fun read(): Seen {
        val surrounding = try {
            connection()?.getSurroundingText(READ_SPAN, READ_SPAN, 0)
        } catch (e: Exception) {
            null
        } ?: return Seen("", -1, "none")
        val text = surrounding.text?.toString().orEmpty()
        val after = text.length - surrounding.selectionEnd
        val full = surrounding.offset == 0 && surrounding.selectionStart < READ_SPAN && after < READ_SPAN
        return Seen(text, surrounding.offset + surrounding.selectionStart, if (full) "full" else "partial")
    }

    private fun selections() = (service.inputMethod as? SessionInputMethod)?.snapshot()?.selections ?: 0

    private fun connection(): InputMethod.AccessibilityInputConnection? = service.inputMethod?.currentInputConnection

    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        main.post {
            try { block() } catch (e: Throwable) { failure = e } finally { done.countDown() }
        }
        check(done.await(MAIN_WAIT_S, TimeUnit.SECONDS)) { "main thread did not run the probe" }
        failure?.let { throw it }
    }

    private companion object {
        const val READ_SPAN = 100_000
        const val WRITES = 10
        const val GAP_MS = 2_000L
        const val SETTLE_MS = 30L
        const val MAIN_WAIT_S = 10L
    }
}
