package com.ezhou.sttbutton

/** Where the stop key goes: the button's last bounds, moved from screen coordinates into a panel that sits at the screen's bottom. */
object StopKeyPlacement {
    fun inPanel(button: Bounds?, screenBottom: Int, panelHeight: Int): Bounds? {
        button ?: return null
        val top = screenBottom - panelHeight
        return Bounds(button.left, button.top - top, button.right, button.bottom - top)
    }
}
