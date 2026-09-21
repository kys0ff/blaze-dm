package org.blaze.data

import kotlinx.serialization.Serializable

/**
 * App-shell settings: how Blaze integrates with the desktop (tray, autostart).
 * Deliberately separate from the engine's [org.blaze.engine.settings.DownloadSettings] —
 * these are GUI concerns, not download-engine concerns.
 */
@Serializable
data class AppSettings(
    /** Show the system-tray icon (ignored when the platform offers no tray). */
    val trayEnabled: Boolean = true,
    /** Hide the window to the tray on close instead of quitting the app. */
    val minimizeToTrayOnClose: Boolean = true,
    /** Register Blaze to start automatically when the user logs in. */
    val runAtStartup: Boolean = false
)
