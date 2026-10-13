package org.island.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogWindowScope
import org.island.foundation.windows.IslandWindowsNativeChrome
import java.awt.Window

/**
 * Compose-side bridge onto the shared Windows DWM caption styler
 * ([org.island.foundation.windows.IslandWindowsNativeChrome]).
 *
 * Compose's [DialogWindowScope] and Jewel's `DecoratedWindow` produce real OS windows; on Windows
 * the caption is painted by DWM outside the app's paint cycle, so the theme's active background has
 * to be pinned to the native caption through the foundation's JNA binding. Everything that is not
 * Windows, and every Windows build without the DWM attributes, is a silent no-op there.
 *
 */

/**
 * Composable effect that keeps a native OS window's Windows title bar painted with the active theme
 * color for as long as the window is in composition, and refreshes it the instant the theme changes.
 *
 * This is the shared engine behind both chrome surfaces that ride a real Windows caption:
 *  - every `DialogWindow` (via the [DialogWindowScope] overload below), and
 *  - the app's main Jewel `DecoratedWindow`, whose OS window frame and border DWM still paints even
 *    though Jewel draws the bar's contents.
 *
 * The caption is re-pinned on every [backgroundColor] change through a keyed effect that does *not*
 * reset in between — a reset would flash the system default before the new color lands, which is
 * visible when the user flips light/dark or edits a theme's colors. The system caption is restored
 * only once, when [window] leaves composition.
 *
 * Dark mode and text contrast are derived from [backgroundColor]'s luminance, so the app's own theme
 * override (light / dark / system) is always respected without passing an explicit flag.
 *
 * @param window the OS window to theme (a `DialogWindowScope.window` or the main frame's window).
 * @param backgroundColor The color to apply to the caption — the dialog's own body background
 *        (`JewelTheme.globalColors.panelBackground`) or the app window's Islands frame color, so
 *        title bar and content read as a single themed surface.
 */
@Composable
fun applyWindowsTitleBarTheme(window: Window, backgroundColor: Color) {
    // Re-pin whenever the theme color changes; deliberately no reset on key change (see KDoc).
    DisposableEffect(window, backgroundColor) {
        IslandWindowsNativeChrome.applyCaptionTheme(window, backgroundColor.toAwtColor())
        onDispose { }
    }
    // Restore the system caption only when the window itself goes away.
    DisposableEffect(window) {
        onDispose {
            IslandWindowsNativeChrome.resetCaptionTheme(window)
        }
    }
}

/**
 * [DialogWindowScope] convenience for [applyWindowsTitleBarTheme], themed from the dialog's own
 * panel background so the caption and body read as one surface. See the dialog-window call sites
 * (`IslandDialog`, `SettingsDialogWindow`, `ConsentDialogWindow`).
 */
@Composable
fun DialogWindowScope.applyWindowsTitleBarTheme(backgroundColor: Color) {
    applyWindowsTitleBarTheme(window, backgroundColor)
}

/** The Compose color as an opaque AWT color — DWM caption colors drop alpha anyway. */
private fun Color.toAwtColor(): java.awt.Color =
    java.awt.Color(
        (red.coerceIn(0f, 1f) * 255).toInt(),
        (green.coerceIn(0f, 1f) * 255).toInt(),
        (blue.coerceIn(0f, 1f) * 255).toInt(),
    )
