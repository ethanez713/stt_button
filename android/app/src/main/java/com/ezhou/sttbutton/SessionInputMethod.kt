package com.ezhou.sttbutton

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import kotlin.jvm.Volatile

/**
 * The editor the keyboard is bound to, recorded from input-session callbacks for the trace.
 * Nothing that shows or hides the button reads it.
 */
class SessionInputMethod(service: AccessibilityService) : InputMethod(service) {
    /** Told, on the main thread, of changes to the editor's text and selection and of input starting. */
    interface Listener {
        fun selectionChanged()
        fun inputStarted(session: InputSession, restarting: Boolean)
    }

    @Volatile
    var listener: Listener? = null

    // Callbacks run on the main thread; the trace may not. One immutable snapshot, so a reader never sees a package from one editor and a count from another.
    @Volatile
    private var current = InputSession.EMPTY

    fun snapshot(): InputSession = current

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val seen = current
        current = seen.copy(
            packageName = attribute.packageName,
            inputType = attribute.inputType,
            starts = seen.starts + 1,
            fieldId = attribute.fieldId,
        )
        listener?.inputStarted(current, restarting)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val seen = current
        current = seen.copy(selections = seen.selections + 1)
        listener?.selectionChanged()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Input has ended, so there is no editor until the next start. The counts stay.
        val seen = current
        current = seen.copy(packageName = null, inputType = 0, fieldId = 0, finishes = seen.finishes + 1)
    }
}

/** One published view of [SessionInputMethod]: the editor, or none between sessions, and how many times input has started and finished and the selection has changed. */
data class InputSession(
    val packageName: String?,
    val inputType: Int,
    val starts: Int,
    val finishes: Int,
    val selections: Int = 0,
    val fieldId: Int = 0,
) {
    fun field() = FieldId(packageName, fieldId)

    /**
     * Whether this session and the field placement read are the same editor: the same package,
     * and the same [FieldPolicy.exclusion] verdict. The session has no password flag of its own;
     * [EditorInfo] carries a password only as an input-type variation.
     */
    fun agrees(fieldPackage: String?, fieldPassword: Boolean, fieldInputType: Int): Boolean =
        packageName == fieldPackage &&
            FieldPolicy.exclusion(password = false, inputType) ==
            FieldPolicy.exclusion(fieldPassword, fieldInputType)

    companion object {
        val EMPTY = InputSession(packageName = null, inputType = 0, starts = 0, finishes = 0)
    }
}

/** An editor as the input method names it: the app and the app's own id for the field. */
data class FieldId(val packageName: String?, val id: Int) {
    /** Whether input starting now, as [restarting] or not, is on another editor than this one. A restart of the same editor is the same field. */
    fun leftBy(start: InputSession, restarting: Boolean): Boolean = !restarting && start.field() != this
}
