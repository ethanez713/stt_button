package com.ezhou.sttbutton

/** What the panel of this app's keyboard draws. */
class PanelState(val stopKey: Bounds?, val heightPx: Int, val busy: Boolean, val stranded: Boolean) {
    companion object {
        val EMPTY = PanelState(stopKey = null, heightPx = 0, busy = false, stranded = false)
    }
}

/**
 * What the keyboard tells the accessibility service and asks of it. The service sets one host for as long
 * as it is connected. The keyboard decides nothing and does not know whether a take is open: it reports,
 * and draws what [panel] says.
 */
interface KeyboardHost {
    /** The stop key was tapped. */
    fun stopKeyTapped()

    fun viewStarted()

    fun viewFinished()

    /** The keyboard attached to a field the button would not show on. */
    fun inputStartedOnExcludedField()

    fun panel(): PanelState
}
