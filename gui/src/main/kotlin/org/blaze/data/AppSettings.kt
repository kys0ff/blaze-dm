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
    val notifyWhileWindowInSight: Boolean = false,

    // ── Downloads tool-window layout ───────────────────────────────────────────
    // Kept as primitives (not a nested @Serializable type from another module) so this file stays
    // self-contained; the view rehydrates them into its own layout model.

    /** Chosen download-table column widths, keyed by column id, in dp. Absent id = default width. */
    val downloadsColumnWidths: Map<String, Float> = emptyMap(),
    /** Name of the [org.blaze.presentation.screens.downloads.DownloadSortField] the list is ordered by. */
    val downloadsSortField: String = "DATE_ADDED",
    /** Direction of the persisted sort (see [downloadsSortField]). */
    val downloadsSortAscending: Boolean = false,
    /**
     * Whether the Details tool window beside the list is shown. Off by default: the pane is a tool
     * window, and a tool window that opens on an empty selection spends 30% of the frame explaining
     * that there is nothing to show. Selecting a row reveals it (see DownloadsScreenModel), and the
     * toolbar / title-bar menu toggle it by hand.
     */
    val detailsPaneVisible: Boolean = false,
    /**
     * Fraction of the list|details split given to the list, persisted across launches. Defaults to the
     * details pane taking about a third (320-380dp on a typical window).
     */
    val detailsSplitFraction: Float = 0.68f,
    /** Confirm before pausing a download whose progress would be discarded. On by default. */
    val warnBeforeDiscardingProgress: Boolean = true,
    /** Name of the [org.blaze.presentation.screens.downloads.DetailsViewMode] the pane presents in. */
    val detailsViewMode: String = "DOCK_PINNED",
    /** Name of the [org.blaze.presentation.screens.downloads.DetailsMoveTo] the pane docks to. */
    val detailsMoveTo: String = "RIGHT_TOP",
    /** Whether the pane's tabs are grouped into one continuous view instead of a tab strip. */
    val groupDetailsTabs: Boolean = false,

    // ── Chrome ─────────────────────────────────────────────────────────────────
    /**
     * The IDE's soft accent-colored glow at the top-left of the window frame (behind the title bar,
     * the tool-window stripe and the island gaps). On by default - it is the New UI's default look -
     * but it is the one piece of chrome that can read as a tint cast over a plain frame, so it is
     * user-switchable under Themes.
     */
    val ambientGlowEnabled: Boolean = true,
)
