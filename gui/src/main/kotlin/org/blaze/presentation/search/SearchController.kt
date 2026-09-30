package org.blaze.presentation.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.blaze.domain.models.Download
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus

/**
 * The single, window-level brain behind Search Everywhere. There is exactly one of these (a Koin
 * singleton), and the three entry points — the title-bar icon, the hamburger item and Ctrl/Cmd+K — all
 * route into it, which is precisely what was missing before: those entry points fired a "focus the
 * in-list filter" command with no popup to open, so clicking search looked like nothing happened.
 *
 * It owns only *interaction state* (open, query, tab, how it was opened) and *activation*. The actual
 * result candidates are built at composition time from live sources: the downloads snapshot the
 * downloads screen pushes in, the static [AppActions] registry and the [org.blaze.presentation.screens.settings.SettingsRegistry].
 * Keeping the data sources out of the controller means the popup always ranks against current state
 * without the controller depending on i18n or a screen model it cannot resolve (the screens are Koin
 * *factories*, one instance per screen).
 */
class SearchController(private val commandBus: AppCommandBus) {

    /** Whether the popup is on screen. Drives both the full-window [Popup] host and the shortcut toggle. */
    var isOpen by mutableStateOf(false)
        private set

    /** The current query, matched per keystroke (no debounce) so results stay live under fast typing. */
    var query by mutableStateOf("")
        private set

    /** The active tab; [SearchTab.All] interleaves every kind under section headers. */
    var tab by mutableStateOf(SearchTab.All)
        private set

    /**
     * Whether the popup was opened from *inside* the Settings window. When true the default tab is
     * Settings and empty-query results lead with settings options, matching the IDE's context-aware
     * Search Everywhere.
     */
    var fromSettings by mutableStateOf(false)
        private set

    // ── Pluggable sources / activation, registered by the screens that own the capability ──────────

    /**
     * The live download list, pushed in by the downloads screen whenever it changes. The popup ranks
     * against this snapshot rather than reaching into a per-screen model it cannot resolve.
     */
    var downloads by mutableStateOf<List<Download>>(emptyList())
        private set

    /**
     * Moves focus to the given download: clears the in-list filter, selects the row, scrolls it into
     * view and opens its details. Registered by the downloads screen (the only place with the list
     * state and the selection model); a no-op until that screen is composed.
     */
    var activateDownload: (id: String) -> Unit by mutableStateOf({})
        private set

    /**
     * The Settings option id the modal window should scroll to and flash once it opens, set when a
     * search result is activated. The Settings screen consumes and clears it.
     */
    var pendingSettingFocus by mutableStateOf<String?>(null)

    // ── Result navigation ──────────────────────────────────────────────────────────────────────────
    // The ranked hits and the highlighted row live here (not only in the popup composable) so the
    // popup's single keyboard handler can move and activate them without reaching back into a child's
    // local state. The popup publishes the hits each time it recomputes them.

    /** The currently ranked results, in the order they are shown; the keyboard walks this list. */
    var hits: List<SearchHit> by mutableStateOf(emptyList())
        private set

    /** The index into [hits] the keyboard/hover currently points at. */
    var highlighted by mutableIntStateOf(0)
        private set

    /** Publishes the freshly ranked [hits], pulling the highlight back in range if the list shrank. */
    fun publishHits(newHits: List<SearchHit>) {
        hits = newHits
        if (highlighted >= newHits.size) highlighted = 0
    }

    fun moveDown() {
        if (hits.isNotEmpty()) highlighted = (highlighted + 1) % hits.size
    }

    fun moveUp() {
        if (hits.isNotEmpty()) highlighted = (highlighted - 1 + hits.size) % hits.size
    }

    /** Steps the highlight by a page (positive down, negative up), clamped rather than wrapping. */
    fun page(delta: Int) {
        if (hits.isEmpty()) return
        highlighted = (highlighted + delta).coerceIn(0, hits.lastIndex)
    }

    /** Sets the highlight directly (a pointer hover). */
    fun highlight(index: Int) {
        if (index in hits.indices) highlighted = index
    }

    /** Activates the highlighted result, if any. */
    fun activateSelected() {
        hits.getOrNull(highlighted)?.let { activate(it) }
    }

    /** Publishes the current download list into the controller (called from the downloads screen). */
    fun submitDownloads(downloads: List<Download>) {
        this.downloads = downloads
    }

    /** Registers the downloads screen's activation callback, returning the previous one for restore. */
    fun registerDownloadActivator(handler: (id: String) -> Unit): (id: String) -> Unit {
        val previous = activateDownload
        activateDownload = handler
        return previous
    }

    // ── Open / close / navigate ────────────────────────────────────────────────────────────────────

    /** Opens the popup. [tab] pins the initial tab; otherwise the context ([fromSettings]) decides it. */
    fun open(tab: SearchTab? = null, fromSettings: Boolean = false) {
        this.fromSettings = fromSettings
        this.tab = tab ?: if (fromSettings) SearchTab.Settings else SearchTab.All
        query = ""
        highlighted = 0
        isOpen = true
    }

    /** Toggles the popup (the Ctrl/Cmd+K chord). */
    fun toggle(fromSettings: Boolean = false) {
        if (isOpen) close() else open(fromSettings = fromSettings)
    }

    fun close() {
        isOpen = false
        query = ""
        highlighted = 0
        hits = emptyList()
    }

    fun updateQuery(value: String) {
        query = value
        highlighted = 0
    }

    fun selectTab(value: SearchTab) {
        tab = value
        highlighted = 0
    }

    /** Steps the tab forward or back, wrapping at the ends (Tab / Shift+Tab, Ctrl+Down / Ctrl+Up). */
    fun cycleTab(delta: Int) {
        val tabs = SearchTab.entries
        val next = (tab.ordinal + delta + tabs.size) % tabs.size
        tab = tabs[next]
        highlighted = 0
    }

    // ── Activation ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the chosen result, then closes the popup. Actions re-raise their [AppCommand] on the shared
     * bus — so a search activation and a menu click of the same action take the exact same path — while
     * downloads and settings hand off to the screens that own that navigation.
     */
    fun activate(hit: SearchHit) {
        when (hit.kind) {
            SearchKind.Download -> activateDownload(hit.id)
            SearchKind.Action -> hit.id.toAppCommand()?.let { commandBus.send(it) }
            SearchKind.Setting -> {
                pendingSettingFocus = hit.id
                commandBus.send(AppCommand.OpenSettings)
            }
        }
        close()
    }

    private fun String.toAppCommand(): AppCommand? =
        runCatching { AppCommand.valueOf(this) }.getOrNull()
}
