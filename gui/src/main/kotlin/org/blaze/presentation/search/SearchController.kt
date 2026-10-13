package org.blaze.presentation.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.blaze.domain.models.Download
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.EnStrings
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.components.BlazeIslandStrings
import org.island.ui.search.IslandSearchController
import org.island.ui.search.SearchHit
import org.island.ui.search.SearchProvider
import org.island.ui.text.IslandUiStrings

/**
 * The single, window-level brain behind Blaze's Search Everywhere. There is exactly one of these (a
 * Koin singleton), and the three entry points — the title-bar icon, the hamburger item and Ctrl/Cmd+K —
 * all route into it.
 *
 * It is Blaze's view of the generic [IslandSearchController]: the app's three result families
 * (downloads, actions, settings) are registered as [SearchProvider]s, and this wrapper keeps the
 * app-side capabilities the popup cannot know about — the live download snapshot the downloads screen
 * pushes in, the per-screen activation callback, the pending settings focus hand-off, and the
 * context-aware default tab ([fromSettings]).
 */
class SearchController(private val commandBus: AppCommandBus) {

    /** The generic controller the island popup renders; Blaze's three families are its providers. */
    val island = IslandSearchController(
        providers = listOf(DownloadsProvider(), ActionsProvider(), SettingsProvider()),
    )

    /** Whether the popup is on screen. Drives both the full-window popup host and the shortcut toggle. */
    val isOpen: Boolean get() = island.isOpen

    /** The current query, matched per keystroke (no debounce) so results stay live under fast typing. */
    val query: String get() = island.query

    /** The active tab; [SearchTab.All] interleaves every kind under section headers. */
    val tab: SearchTab
        get() = SearchTab.entries.first { it.tabId == island.tabId }

    /**
     * Whether the popup was opened from *inside* the Settings window. When true the default tab is
     * Settings and empty-query results lead with settings options, matching the IDE's context-aware
     * Search Everywhere.
     */
    var fromSettings by mutableStateOf(false)
        private set

    // ── Pluggable sources / activation, registered by the screens that own the capability ──────────

    /**
     * The live download list, pushed in by the downloads screen whenever it changes. [DownloadsProvider]
     * reads it as snapshot state, so the popup re-ranks against it without this controller reaching
     * into a per-screen model it cannot resolve.
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
        island.open(
            tabId = tab?.tabId,
            defaultTabId = if (fromSettings) SearchTab.Settings.tabId else SearchTab.All.tabId,
        )
    }

    /** Toggles the popup (the Ctrl/Cmd+K chord). */
    fun toggle(fromSettings: Boolean = false) {
        if (isOpen) close() else open(fromSettings = fromSettings)
    }

    fun close() = island.close()

    fun updateQuery(value: String) = island.updateQuery(value)

    // ── Providers ──────────────────────────────────────────────────────────────────────────────────

    /** The Blaze strings behind the island contract (providers label and match through them). */
    private fun blazeOf(strings: IslandUiStrings): BlazeStrings =
        (strings as? BlazeIslandStrings)?.blaze ?: EnStrings

    /** The download list, exactly as the on-screen table shows it. */
    private inner class DownloadsProvider : SearchProvider {
        override val id = SearchKind.Download.groupId

        override fun tabLabel(strings: IslandUiStrings) = blazeOf(strings).search.tabDownloads

        override fun sectionTitle(strings: IslandUiStrings) = blazeOf(strings).search.sectionDownloads

        override fun candidates(query: String, strings: IslandUiStrings) =
            if (query.isBlank()) {
                // The at-rest Downloads tab previews the most recent rows, like the IDE's tool-window lists.
                SearchSources.downloadCandidates(downloads.take(SearchSources.BLANK_DOWNLOAD_LIMIT), blazeOf(strings))
            } else {
                SearchSources.downloadCandidates(downloads, blazeOf(strings))
            }

        override fun onActivate(hit: SearchHit) = activateDownload(hit.id)
    }

    /** The hamburger menu's command registry — activating a result re-raises the very same command. */
    private inner class ActionsProvider : SearchProvider {
        override val id = SearchKind.Action.groupId

        override fun tabLabel(strings: IslandUiStrings) = blazeOf(strings).search.tabActions

        override fun sectionTitle(strings: IslandUiStrings) = blazeOf(strings).search.sectionActions

        override fun candidates(query: String, strings: IslandUiStrings) =
            SearchSources.actionCandidates(blazeOf(strings))

        override fun onActivate(hit: SearchHit) {
            runCatching { AppCommand.valueOf(hit.id) }.getOrNull()?.let(commandBus::send)
        }
    }

    /** Every registered settings option, matched against its label, English alias and keywords. */
    private inner class SettingsProvider : SearchProvider {
        override val id = SearchKind.Setting.groupId

        override fun tabLabel(strings: IslandUiStrings) = blazeOf(strings).search.tabSettings

        override fun sectionTitle(strings: IslandUiStrings) = blazeOf(strings).search.sectionSettings

        override fun candidates(query: String, strings: IslandUiStrings) =
            SearchSources.settingCandidates(blazeOf(strings))

        override fun onActivate(hit: SearchHit) {
            pendingSettingFocus = hit.id
            commandBus.send(AppCommand.OpenSettings)
        }
    }
}
