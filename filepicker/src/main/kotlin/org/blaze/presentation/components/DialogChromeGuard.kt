package org.blaze.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.DialogWindowScope
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton

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
 * The frame can settle the question with a single fact: **a dialog is currently open**. Blaze's
 * main frame is a Jewel `DecoratedWindow` whose title-bar buttons live in the window's own
 * content, and a composed dialog is document-modal, so while one is open the user cannot even
 * drive a frame-level minimize through the app; the only way this frame reports ICONIFIED is a
 * window-manager group minimize that started at the dialog's caption. With no dialog open the
 * fold behaves exactly as before.
 */
object DialogChromeGuard {

    private val openDialogs = ConcurrentHashMap.newKeySet<Window>()

    /** Track a live dialog for as long as it is composed (see [preventDialogMinimize]). */
    internal fun track(dialog: Window) {
        openDialogs.add(dialog)
    }

    internal fun untrack(dialog: Window) {
        openDialogs.remove(dialog)
    }

    /**
     * Whether a frame iconification should be read as fallout from a dialog minimize rather than
     * an intent to hide the app.
     */
    fun hasOpenDialog(): Boolean = openDialogs.isNotEmpty()

    /** Clears the registry; only tests reach for it. */
    internal fun reset() {
        openDialogs.clear()
    }
}

/**
 * Call inside a `DialogWindow` content lambda to make its minimize button inert, the way an IDE
 * modal never lets its dialog captions minimize:
 *
 *  - The dialog registers with [DialogChromeGuard], so any group-iconification of the owner frame
 *    that its minimize triggers is undone instead of hiding the app to the tray. (Restoring the
 *    frame restores the transient group, and with it the dialog, on window managers that group.)
 *  - A state listener on the dialog itself undoes direct iconifications on platforms whose
 *    toolkit reports them (best-effort: `java.awt.Dialog` carries no iconable state API, so the
 *    undo can only reach for `toFront`).
 *  - On runtimes where Swing paints the decorations, the caption's minimize button is grayed out
 *    best-effort, so the dialog reads as non-minimizable before anything is clicked - the
 *    IntelliJ look.
 */
@Composable
fun DialogWindowScope.preventDialogMinimize() {
    val dialog = window
    DisposableEffect(dialog) {
        val listener = object : WindowAdapter() {
            override fun windowStateChanged(e: WindowEvent) {
                if (e.newState and Frame.ICONIFIED != 0) {
                    // Undo however this platform lets us; the frame-side group restore covers the
                    // rest. java.awt.Dialog carries no iconable state API, so raising it is all
                    // there is to reach for here.
                    runCatching { dialog.toFront() }
                }
            }
        }
        dialog.addWindowStateListener(listener)
        DialogChromeGuard.track(dialog)
        disableCaptionMinimizeButton(dialog)
        onDispose {
            DialogChromeGuard.untrack(dialog)
            dialog.removeWindowStateListener(listener)
        }
    }
}

/**
 * Best-effort graying of the caption's minimize button when Swing paints the decorations itself
 * (same `BasicTitlePane` walk the consent dialog uses for its close button). With native
 * decorations there is nothing to reach, and the registration above is what keeps the click
 * harmless anyway.
 */
private fun disableCaptionMinimizeButton(dialog: Window) {
    runCatching {
        fun walk(component: Component) {
            when {
                component.javaClass.name.endsWith("BasicTitlePane") -> disableMinimizeIn(component)
                component is Container -> component.components.forEach(::walk)
            }
        }
        (dialog as? javax.swing.JDialog)?.rootPane?.components?.forEach(::walk)
    }
}

private fun disableMinimizeIn(titlePane: Component) {
    // BasicTitlePane keeps its caption buttons in protected fields; reach the minimize one
    // reflectively, falling back to the accessibility name on runtimes that rename it.
    val minButton = sequenceOf("minButton", "minimizeButton").firstNotNullOfOrNull { name ->
        runCatching {
            titlePane.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(titlePane)
        }.getOrNull()
    }
    if (minButton is JButton) {
        minButton.isEnabled = false
        return
    }
    if (titlePane is Container) {
        titlePane.components.filterIsInstance<JButton>().forEach { button ->
            val accessible = button.accessibleContext?.accessibleName.orEmpty()
            if (accessible.contains("minimize", ignoreCase = true) ||
                accessible.contains("iconify", ignoreCase = true)
            ) {
                button.isEnabled = false
            }
        }
    }
}
