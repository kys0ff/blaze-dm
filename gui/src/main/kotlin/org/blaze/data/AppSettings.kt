package org.blaze.data

import kotlinx.serialization.Serializable

/**
 * App-shell settings: how Blaze integrates with the desktop (tray, autostart).
 * Deliberately separate from the engine's [org.blaze.engine.settings.DownloadSettings] —
 * these are GUI concerns, not download-engine concerns.
 */
@Serializable
data class AppSettings(
    /**
     * UI language as a [org.blaze.i18n.BlazeLocale] code ("en", "ar", ...). Unknown codes fall
     * back to the default locale, so a hand-edited file can never leave the UI without strings.
     */
    val languageCode: String = "en",
    /** Show the system-tray icon (ignored when the platform offers no tray). */
    val trayEnabled: Boolean = true,
    /** Hide the window to the tray on close instead of quitting the app. */
    val minimizeToTrayOnClose: Boolean = true,
    /** Register Blaze to start automatically when the user logs in. */
    val runAtStartup: Boolean = false,
    /**
     * Watch the system clipboard for copied web links, magnets or `.torrent` paths and offer them
     * as downloads. Off by default: reading the clipboard is a user-visible side effect, so it
     * must be opted into, not granted silently.
     */
    val clipboardMonitoringEnabled: Boolean = false
)
