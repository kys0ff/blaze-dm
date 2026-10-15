package org.island.ui.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.DialogWindowScope
import org.island.foundation.window.DialogChromeGuard
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JButton

/**
 * Compose-side half of the shared dialog-chrome guard
 * ([org.island.foundation.window.DialogChromeGuard]); the open-dialog registry itself is plain AWT
 * and lives in `:island-foundation` so the main frame's state listener can read it without pulling
 * any Compose types down.
 *
 */

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
