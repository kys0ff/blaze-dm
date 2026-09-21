package org.blaze.platform.tray

/**
 * Contract for a system-tray / status-bar presence. Implementations must degrade
 * gracefully: when the desktop environment offers no tray, [isSupported] is false
 * and [install] is a no-op, so callers never need platform checks of their own.
 */
interface TrayService {
    /** Whether this platform/environment can actually show a tray icon. */
    val isSupported: Boolean

    /**
     * (Re)install the tray icon with a menu labeled via [labels] whose items invoke
     * [actions]. Installing over an existing icon replaces it. No-op when unsupported.
     */
    fun install(labels: TrayLabels, actions: TrayActions)

    /**
     * Keep the Show/Hide menu item in sync with the window's visibility
     * (a hidden window offers "Show", a visible one offers "Hide").
     */
    fun setWindowVisible(visible: Boolean)

    /** Remove the tray icon; safe to call when nothing was ever installed. */
    fun dispose()
}

/** Menu captions, supplied by the caller so the tray follows the app's locale. */
data class TrayLabels(
    val show: String,
    val hide: String,
    val pauseAll: String,
    val resumeAll: String,
    val quit: String
)

/**
 * Callbacks invoked from the AWT event-dispatch thread when a menu item is clicked.
 * Implementations must not block; long-running work should be dispatched elsewhere.
 */
data class TrayActions(
    val onToggleWindow: () -> Unit,
    val onPauseAll: () -> Unit,
    val onResumeAll: () -> Unit,
    val onQuit: () -> Unit
)
