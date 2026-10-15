package org.island.foundation.window

import java.awt.Window
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide registry of composed dialog windows, the cross-platform answer to dialog minimize
 * buttons stealing the main frame's "minimize means hide to tray" fold.
 *
 * A Compose `DialogWindow` is a real OS window owned by the main frame, and its caption (native or
 * Swing-painted, depending on platform) often offers a minimize button. Pressing it iconifies the
 * dialog, and window managers that iconify transient groups - the classic KDE behavior - iconify
 * the owner frame with it, which the frame's state listener would otherwise read as a deliberate
 * main-window minimize and hide the whole app to the tray.
 *
 * The frame can settle the question with a single fact: **a dialog is currently open**. The app's
 * main frame is a Jewel `DecoratedWindow` whose title-bar buttons live in the window's own
 * content, and a composed dialog is document-modal, so while one is open the user cannot even
 * drive a frame-level minimize through the app; the only way this frame reports ICONIFIED is a
 * window-manager group minimize that started at the dialog's caption. With no dialog open the
 * fold behaves exactly as before.
 *
 * The Compose-side half of this guard (`preventDialogMinimize`, which registers a `DialogWindow`
 * while it is composed) lives in `island-ui`; this registry is the plain-AWT fact both the dialogs
 * and the frame's state listener read.
 */
object DialogChromeGuard {

    private val openDialogs = ConcurrentHashMap.newKeySet<Window>()

    /** Track a live dialog for as long as it is composed (see `preventDialogMinimize`). */
    fun track(dialog: Window) {
        openDialogs.add(dialog)
    }

    fun untrack(dialog: Window) {
        openDialogs.remove(dialog)
    }

    /**
     * Whether a frame iconification should be read as fallout from a dialog minimize rather than
     * an intent to hide the app.
     */
    fun hasOpenDialog(): Boolean = openDialogs.isNotEmpty()

    /** Clears the registry; only tests reach for it. */
    fun reset() {
        openDialogs.clear()
    }
}
