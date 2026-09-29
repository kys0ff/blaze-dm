package org.blaze.data

import kotlinx.serialization.Serializable

/**
 * App-shell settings: how Blaze integrates with the desktop (tray, autostart, notifications).
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
    val clipboardMonitoringEnabled: Boolean = false,
    /**
     * Master switch for desktop (toast / notification-center) notices about download lifecycle
     * changes. In-app balloons are unaffected — they belong to the window, not the desktop.
     */
    val desktopNotificationsEnabled: Boolean = true,
    /** Post a notice when a download finishes. */
    val notifyOnCompleted: Boolean = true,
    /** Post a notice when a download fails for good (after its retries are spent). */
    val notifyOnFailed: Boolean = true,
    /** Post a notice when an unfinished transfer is forced to start over from zero. */
    val notifyOnRestarted: Boolean = true,
    /**
     * Also notify while the Blaze window is visible and focused. Off by default: a user staring
     * at the list has already seen the row change, so a desktop banner would only repeat it.
     */
    val notifyWhileWindowInSight: Boolean = false
)
