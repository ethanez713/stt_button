package com.ezhou.sttbutton

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.SoftKeyboardController
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager

/**
 * Switches the phone's keyboard to this app's for a take and back. Which order the return tries
 * is [ImeReturn]'s; this class owns the Android calls and the preferences that survive a process death.
 */
class ImeSwapper(private val service: AccessibilityService) {
    private val tag = DictationService.TAG
    private val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val imm = service.getSystemService(InputMethodManager::class.java)
    private val ourId = ComponentName(service, DictationIme::class.java).flattenToShortString()
    private var enabled = false

    val currentDefault: String? get() = Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    fun enableOnConnect() {
        val result = service.softKeyboardController.setInputMethodEnabled(ourId, true)
        enabled = result == SoftKeyboardController.ENABLE_IME_SUCCESS
        Log.i(tag, "ime enable $ourId result=${enableName(result)}")
    }

    /** Remembers the user's keyboard and asks for this app's. [SwapState.off] when it is not enabled or already the default. */
    fun begin(nowMs: Long): SwapState {
        val default = currentDefault
        if (!enabled || default == null || default == ourId) return SwapState.off()
        val subtype = Settings.Secure.getString(service.contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE)?.toIntOrNull()
        prefs.edit().putString(KEY_ID, default).putInt(KEY_SUBTYPE, subtype ?: NO_SUBTYPE).apply()
        val switched = service.softKeyboardController.switchToInputMethod(ourId)
        Log.i(tag, "ime swap from=$default switched=$switched")
        return SwapState.started(switched, nowMs)
    }

    /** Puts the user's keyboard back; the name of the first method that worked, or null when none did. */
    fun returnKeyboard(): String? {
        val ime = ImeLink.current
        val plan = ImeReturn.plan(saved(), gboardId(), imeAlive = ime != null)
        for (step in plan) {
            val worked = try {
                when (step.method) {
                    ReturnMethod.SUBTYPE -> {
                        val subtype = ImeSubtypes.find(imm, step.imeId, step.subtypeHash)
                        @Suppress("DEPRECATION")
                        if (subtype != null) ime!!.switchInputMethod(step.imeId!!, subtype) else ime!!.switchInputMethod(step.imeId!!)
                        true
                    }
                    ReturnMethod.PREVIOUS -> ime!!.switchToPreviousInputMethod()
                    ReturnMethod.CONTROLLER -> service.softKeyboardController.switchToInputMethod(step.imeId!!)
                }
            } catch (e: Exception) {
                Log.w(tag, "ime return ${step.method} failed", e)
                false
            }
            if (worked) {
                Log.i(tag, "ime return via ${step.method}")
                ImeLink.stranded = false
                return step.method.name.lowercase()
            }
        }
        Log.w(tag, "ime return: nothing worked, plan had ${plan.size} steps")
        ImeLink.stranded = true
        ImeLink.current?.refreshPanel()
        return null
    }

    /** A process death mid-take leaves this app's keyboard as the default. */
    fun returnIfStranded() {
        if (currentDefault == ourId && ImeLink.swap == null) returnKeyboard()
    }

    private fun saved(): SavedIme? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        return SavedIme(id, prefs.getInt(KEY_SUBTYPE, NO_SUBTYPE).takeIf { it != NO_SUBTYPE && it != -1 })
    }

    private fun gboardId(): String? = imm.enabledInputMethodList.firstOrNull { it.packageName == MicLocator.GBOARD }?.id

    private fun enableName(result: Int) = when (result) {
        SoftKeyboardController.ENABLE_IME_SUCCESS -> "ENABLE_IME_SUCCESS"
        SoftKeyboardController.ENABLE_IME_FAIL_BY_ADMIN -> "ENABLE_IME_FAIL_BY_ADMIN"
        SoftKeyboardController.ENABLE_IME_FAIL_UNKNOWN -> "ENABLE_IME_FAIL_UNKNOWN"
        else -> "unknown($result)"
    }

    companion object {
        private const val PREFS = "ime"
        private const val KEY_ID = "saved_id"
        private const val KEY_SUBTYPE = "saved_subtype"
        private const val NO_SUBTYPE = Int.MIN_VALUE
    }
}
