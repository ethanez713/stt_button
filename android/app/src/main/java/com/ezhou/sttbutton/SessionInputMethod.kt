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
        )
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Input has ended, so there is no editor until the next start. The counts stay.
        val seen = current
        current = seen.copy(packageName = null, inputType = 0, finishes = seen.finishes + 1)
    }
}

/** One published view of [SessionInputMethod]: the editor, or none between sessions, and how many times input has started and finished. */
data class InputSession(
    val packageName: String?,
    val inputType: Int,
    val starts: Int,
    val finishes: Int,
) {
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
