package com.ezhou.sttbutton

import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

object ImeSubtypes {
    /** The subtype of [imeId] whose id is [hash], which is what the subtype setting holds; null when either is unknown. */
    fun find(imm: InputMethodManager, imeId: String?, hash: Int?): InputMethodSubtype? {
        if (hash == null || hash == -1) return null
        val info = imm.inputMethodList.firstOrNull { it.id == imeId } ?: return null
        val subtypes = (0 until info.subtypeCount).map(info::getSubtypeAt) +
            imm.getEnabledInputMethodSubtypeList(info, true)
        // hashCode() is the explicit subtype id when one was set, and the settings value is that same number.
        return subtypes.firstOrNull { it.hashCode() == hash }
    }
}
