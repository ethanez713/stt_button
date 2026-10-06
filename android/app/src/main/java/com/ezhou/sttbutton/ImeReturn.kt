package com.ezhou.sttbutton

enum class ReturnMethod { SUBTYPE, PREVIOUS, CONTROLLER }

data class SavedIme(val id: String, val subtypeHash: Int?)

data class ReturnStep(val method: ReturnMethod, val imeId: String?, val subtypeHash: Int?)

/** The order in which to try putting the user's keyboard back, from what is known and what is alive. */
object ImeReturn {
    /** [gboardId] is the first enabled Gboard keyboard, the target when nothing was saved. [imeAlive] is whether this app's keyboard is running to switch from. */
    fun plan(saved: SavedIme?, gboardId: String?, imeAlive: Boolean): List<ReturnStep> {
        val target = saved?.id ?: gboardId
        val subtype = saved?.subtypeHash
        return buildList {
            if (imeAlive) {
                if (target != null) add(ReturnStep(ReturnMethod.SUBTYPE, target, subtype))
                add(ReturnStep(ReturnMethod.PREVIOUS, null, null))
            }
            if (target != null) add(ReturnStep(ReturnMethod.CONTROLLER, target, null))
        }
    }
}
