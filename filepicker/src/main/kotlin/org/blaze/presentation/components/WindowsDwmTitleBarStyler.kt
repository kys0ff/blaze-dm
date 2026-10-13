package org.blaze.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogWindowScope
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window

/**
 * Windows DWM (Desktop Window Manager) title-bar integration.
 *
 * Compose's [DialogWindowScope] produces a real OS window; on Windows the caption (title bar) is
 * rendered by DWM outside the application's paint cycle, so no Compose background modifier can reach
 * it. The OS exposes
 * [`DwmSetWindowAttribute`](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/nf-dwmapi-dwmsetwindowattribute)
 * which lets a process set the caption color, border color, and title text color per-window,
 * following the same rules DWM uses for UWP and WinUI apps.
 *
 * This mirrors what IntelliJ IDEA does natively: it learns the active palette's panel background
 * (the color the dialog body paints with) and pins the DWM caption to that color, so the dialog
 * reads as a single themed surface rather than a Compose panel glued beneath an off-theme bar.
 *
 * **Availability:**
 * - `DWMWA_USE_IMMERSIVE_DARK_MODE` (20): Windows 10 1809+ — gives a dark caption when exact color
 *   control is unavailable.
 * - `DWMWA_CAPTION_COLOR` (35), `DWMWA_BORDER_COLOR` (34), `DWMWA_TEXT_COLOR` (36): Windows 11
 *   22000+ — exact per-window color override.
 *
 * On older Windows (or non-Windows hosts) every call is a silent no-op.
 */
internal object WindowsDwmTitleBarStyler {

    // DWM attribute indices (dwmapi.h, winuser.h).
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    // Sentinel DWM interprets as "use the system default" for caption/border/text.
    private const val DWMWA_COLOR_DEFAULT = -1 // 0xFFFFFFFF as signed Int

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    /** Lazily loaded dwmapi binding; null on non-Windows hosts or if the library can't be found. */
    private val dwmApi: IDwmApi? = if (isWindows) runCatching {
        Native.load("dwmapi", IDwmApi::class.java)
    }.getOrNull() else null

    /**
     * Applies the given panel background [color] to the dialog's native title bar via DWM.
     * Dark mode and text contrast are derived from [color]'s luminance — the same Rec. 601 luma
     * rule Jewel applies internally for window-control glyph selection — so the app's own theme
     * override (light / dark / system) is always respected without an explicit flag.
     *
     * A silent no-op on non-Windows hosts, when dwmapi cannot be loaded, or when the window's
     * native handle is not yet available (the peer is absent before the first `setVisible(true)`).
     */
    fun applyTheme(window: Window, color: Color) {
        val api = dwmApi ?: return
        val hwnd = getWindowHandle(window) ?: return
        val isDark = color.isDarkColor()
        val colorRef = color.toColorRef()
        // White text on dark backgrounds; black on light.
        val textColorRef = if (isDark) 0x00FFFFFF else 0x00000000
        setInt(api, hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, if (isDark) 1 else 0)
        setInt(api, hwnd, DWMWA_CAPTION_COLOR, colorRef)
        setInt(api, hwnd, DWMWA_BORDER_COLOR, colorRef)
        setInt(api, hwnd, DWMWA_TEXT_COLOR, textColorRef)
    }

    /** Resets the DWM caption to the system default; called when the dialog leaves composition. */
    fun resetTheme(window: Window) {
        val api = dwmApi ?: return
        val hwnd = getWindowHandle(window) ?: return
        setInt(api, hwnd, DWMWA_CAPTION_COLOR, DWMWA_COLOR_DEFAULT)
        setInt(api, hwnd, DWMWA_BORDER_COLOR, DWMWA_COLOR_DEFAULT)
        setInt(api, hwnd, DWMWA_TEXT_COLOR, DWMWA_COLOR_DEFAULT)
        setInt(api, hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 0)
    }

    /**
     * Extracts the native HWND pointer from an AWT [Window] via JNA's built-in window-handle
     * resolution (`Native.getWindowPointer`). JNA reaches the AWT peer through its own JNI bridge,
     * so no `--add-opens java.desktop/sun.awt.windows=ALL-UNNAMED` is required.
     *
     * Returns `null` if the window is not yet displayable (peer absent) or the call fails.
     */
    private fun getWindowHandle(window: Window): Pointer? = runCatching {
        Native.getWindowPointer(window).takeIf { it != Pointer.NULL }
    }.getOrNull()

    /** Writes [value] into a native int buffer and calls `DwmSetWindowAttribute`. */
    private fun setInt(api: IDwmApi, hwnd: Pointer, attribute: Int, value: Int) {
        val buf = IntArray(1)
        buf[0] = value
        // pvAttribute is a void*; JNA marshals an IntArray as a pointer to its first element,
        // which is exactly what DwmSetWindowAttribute expects for DWORD-sized attributes.
        api.DwmSetWindowAttribute(hwnd, attribute, buf, Int.SIZE_BYTES)
    }
}

// ── JNA interface ─────────────────────────────────────────────────────────────────────────────────

/**
 * Minimal JNA binding for `dwmapi.dll`. Only `DwmSetWindowAttribute` is declared; JNA's dynamic
 * proxy generates the stub lazily on first use. The `hwnd` is a raw [Pointer] (rather than the
 * `WinDef.HWND` wrapper from `jna-platform`) so the binding is self-contained with only the base
 * `jna` artifact.
 *
 * C signature: `HRESULT DwmSetWindowAttribute(HWND hwnd, DWORD dwAttribute, PVOID pvAttribute, DWORD cbAttribute)`
 */
@Suppress("FunctionName")
private interface IDwmApi : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, dwAttribute: Int, pvAttribute: IntArray, cbAttribute: Int): Int
}

// ── Composable extension ──────────────────────────────────────────────────────────────────────────

/**
 * Composable effect that keeps the dialog window's native Windows title bar painted with the
 * active panel background color for as long as the dialog is in composition.
 *
 * Call from inside the `DialogWindow { … }` lambda:
 * ```kotlin
 * DialogWindow(…) {
 *     applyWindowsTitleBarTheme(JewelTheme.globalColors.panelBackground)
 *     // … content …
 * }
 * ```
 *
 * No-ops on non-Windows platforms (where Jewel's own chrome or the Linux WM handles decorations)
 * and on Windows builds older than 1809 where the DWM attributes are absent.
 *
 * Dark mode and text contrast are derived from [backgroundColor]'s luminance, so the app's own
 * theme override (light / dark / system) is always respected without passing an explicit flag.
 *
 * @param backgroundColor The color to apply to the caption — typically the dialog's own body
 *        background (`JewelTheme.globalColors.panelBackground`), so title bar and content read
 *        as a single themed surface.
 */
@Composable
fun DialogWindowScope.applyWindowsTitleBarTheme(backgroundColor: Color) {
    DisposableEffect(window, backgroundColor) {
        WindowsDwmTitleBarStyler.applyTheme(window, backgroundColor)
        onDispose {
            WindowsDwmTitleBarStyler.resetTheme(window)
        }
    }
}

// ── Color helpers ─────────────────────────────────────────────────────────────────────────────────

/**
 * Whether a Compose [Color] reads as a dark background, using the Rec. 601 luma coefficient rule
 * Jewel applies internally to select light vs. dark window-control glyphs.
 * Alpha is ignored (matching Jewel's `isDark()`: the RGB channels alone carry the design intent).
 */
private fun Color.isDarkColor(): Boolean {
    // Y = 0.299R + 0.587G + 0.114B; threshold at 50 %.
    val luma = 0.299f * red + 0.587f * green + 0.114f * blue
    return luma < 0.5f
}

/**
 * Converts a Compose [Color] to a Windows `COLORREF` integer (`0x00BBGGRR`).
 * Alpha is dropped — DWM caption colors are always opaque.
 */
private fun Color.toColorRef(): Int {
    val r = (red.coerceIn(0f, 1f) * 255).toInt()
    val g = (green.coerceIn(0f, 1f) * 255).toInt()
    val b = (blue.coerceIn(0f, 1f) * 255).toInt()
    return (b shl 16) or (g shl 8) or r
}
