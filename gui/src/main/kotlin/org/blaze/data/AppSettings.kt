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
     * When a `.torrent` download link (an http/https URL serving metainfo) is added, fetch the torrent
     * file first, preview the files inside it, and then queue the content as a normal local torrent
     * instead of leaving the engine to resolve the URL through the swarm. Off by default: it reaches out
     * to the URL and writes a cached metainfo file on the user's behalf, so it must be opted into.
     */
    val torrentFilePreviewEnabled: Boolean = false,
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
    /**
     * Ids of the download-table columns the user hid from the header's Table options menu. Stored as a
     * list of ids (not a set type) to keep this file's "primitives only" rule for persisted layout.
     */
    val downloadsHiddenColumns: List<String> = emptyList(),
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
    val detailsInnerSplitFraction: Float = 0.50f,
    val detailsFloatingOffsetX: Float = 40f,
    val detailsFloatingOffsetY: Float = 40f,
    val detailsFloatingWidthDp: Float = 380f,
    val detailsFloatingHeightDp: Float = 480f,

    // ── Server Logs tool-window layout ────────────────────────────────────────
    // Mirrors the Details pane's fields above; the log window reuses the same view-mode / move-to
    // enums, so its persisted layout is the same shape (primitives only, rehydrated by the view).

    /** Whether the dockable Server Logs tool window is shown. Off by default: it is a tool window. */
    val serverLogVisible: Boolean = false,
    /** Fraction of the content|log split given to the content, persisted across launches. */
    val serverLogSplitFraction: Float = 0.65f,
    /** Name of the [org.blaze.presentation.screens.downloads.DetailsViewMode] the log window presents in. */
    val serverLogViewMode: String = "DOCK_PINNED",
    /** Name of the [org.blaze.presentation.screens.downloads.DetailsMoveTo] the log window docks to. */
    val serverLogMoveTo: String = "BOTTOM_RIGHT",
    val serverLogInnerSplitFraction: Float = 0.50f,
    val serverLogFloatingOffsetX: Float = 80f,
    val serverLogFloatingOffsetY: Float = 80f,
    val serverLogFloatingWidthDp: Float = 450f,
    val serverLogFloatingHeightDp: Float = 320f,

    // ── Extensions tool-window layout ─────────────────────────────────────────
    // The Extensions live manager is a dockable tool window beside the downloads list, not a full
    // screen; it reuses the Details/Server-Log view-mode + move-to enums, so its persisted layout is
    // the same shape (primitives only, rehydrated by the model).

    /** Whether the dockable Extensions tool window is shown. Off by default: it is a tool window. */
    val extensionsVisible: Boolean = false,
    /** Fraction of the content|extensions split given to the content, persisted across launches. */
    val extensionsSplitFraction: Float = 0.65f,
    /** Name of the [org.blaze.presentation.screens.downloads.DetailsViewMode] the window presents in. */
    val extensionsViewMode: String = "DOCK_PINNED",
    /** Name of the [org.blaze.presentation.screens.downloads.DetailsMoveTo] the window docks to. */
    val extensionsMoveTo: String = "LEFT_TOP",
    val extensionsInnerSplitFraction: Float = 0.50f,
    val extensionsFloatingOffsetX: Float = 120f,
    val extensionsFloatingOffsetY: Float = 120f,
    val extensionsFloatingWidthDp: Float = 420f,
    val extensionsFloatingHeightDp: Float = 480f,

    // ── Chrome ─────────────────────────────────────────────────────────────────
    /**
     * The IDE's soft accent-colored glow at the top-left of the window frame (behind the title bar,
     * the tool-window stripe and the island gaps). On by default - it is the New UI's default look -
     * but it is the one piece of chrome that can read as a tint cast over a plain frame, so it is
     * user-switchable under Themes.
     */
    val ambientGlowEnabled: Boolean = true,

    // ── Keyboard shortcuts ───────────────────────────────────────────────────
    /**
     * User rebinds, keyed by [org.blaze.presentation.components.BlazeKeymap.Action] name. An absent key
     * means "use the built-in default"; a stored value overrides it. Stored as primitives (raw key code +
     * location + modifier flags) so this file keeps its "no foreign serializable types" rule.
     */
    val keyBindings: Map<String, StoredKeyBinding> = emptyMap(),
    /**
     * Action names the user explicitly unbound — the action then fires on no key and prints no accelerator.
     * Kept separate from [keyBindings] so "unbound" is distinct from "default"; cleared by a per-row reset.
     */
    val unboundShortcuts: List<String> = emptyList(),

    // ── File pickers ───────────────────────────────────────────────────────────
    /**
     * The directory each file picker reopens at, keyed by the name of its
     * [org.blaze.presentation.screens.filepicker.model.FilePickerContext] and stored as a path string —
     * kept as primitives, like the layout fields above, so this file stays self-contained.
     *
     * Keyed per context deliberately: the folder someone browsed to in order to install an extension jar
     * says nothing about where downloads should land, and one shared slot would have the two dialogs
     * fighting over it every time either is opened.
     *
     * Values are advisory: an unknown key or a directory that no longer exists is ignored, and the picker
     * opens at home instead.
     */
    val filePickerLastDirectories: Map<String, String> = emptyMap(),

    // ── Security & consent ───────────────────────────────────────────────────
    /**
     * Ask the user to acknowledge every *new* third-party client before it may drive Blaze through
     * the local API (allow once / always / reject). Switching this off is the deliberate override:
     * any token-holding caller gets through without a prompt, so only do it on a trusted machine.
     */
    val approveExternalClients: Boolean = true,
    /** Third-party clients the user chose "allow always" for; each is prompt-free from now on. */
    val rememberedClients: List<RememberedClient> = emptyList(),
    /**
     * Ask the user before an untrusted extension jar (link handler or theme) is installed or loaded.
     * Extension code runs inside Blaze with the app's own permissions, so this is the prompt that
     * stands between a hostile jar and executing. Off = load without asking.
     */
    val approveExtensions: Boolean = true,
    /** Extension jars the user chose "allow always" for, keyed by content hash. */
    val trustedExtensions: List<TrustedExtension> = emptyList(),
)

/**
 * A keyboard chord as written to disk: a physical key ([keyCode]/[keyLocation] mirror `java.awt.event.KeyEvent`)
 * under the primary [ctrl] (Ctrl on Windows/Linux, Command on macOS), [shift] and [alt] modifiers.
 * [KeymapPersistence] converts to and from the presentation [org.blaze.presentation.components.BlazeKeymap.Binding].
 */
@Serializable
data class StoredKeyBinding(
    val keyCode: Int,
    val keyLocation: Int = 0,
    val ctrl: Boolean = true,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/**
 * A local-API client the user permanently approved through the consent dialog.
 * [id] is the stable client identity (the `X-Blaze-Client-Id` header, or the server-derived fallback);
 * [displayName] is only remembered so the Settings manager can show a human-readable label.
 */
@Serializable
data class RememberedClient(
    val id: String,
    val displayName: String,
    val approvedAtEpochMs: Long = 0L,
)

/**
 * An extension jar the user permanently approved, identified by the SHA-256 [sha256] of its bytes
 * (renaming the file cannot reuse the approval). [kind] is "LINK_RESOLVER" or "THEME", so trusting
 * one kind never vouches for the other.
 */
@Serializable
data class TrustedExtension(
    val sha256: String,
    val fileName: String,
    val kind: String,
    val approvedAtEpochMs: Long = 0L,
)
