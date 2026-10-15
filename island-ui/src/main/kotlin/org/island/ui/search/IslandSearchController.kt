package org.island.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.island.ui.text.IslandUiStrings

/**
 * The generic window-level brain behind Search Everywhere: interaction state (open, query, tab),
 * result navigation and activation, wired to the app's [SearchProvider] families.
 *
 * It owns no data of its own — candidates are pulled from the providers at query time, so the popup
 * always ranks against live app state without this controller depending on any screen or repository.
 * The ranked hits live here (not only in the popup composable) so the popup's single keyboard handler
 * can move and activate them; the popup publishes them back through [publishHits] each time it
 * recomputes.
 *
 * Tabs are the built-in [ALL_TAB_ID] followed by one per provider, in provider order. The All tab
 * interleaves every family under section headers, keeping each family's internal order — exactly the
 * shape the IDE draws.
 */
class IslandSearchController(val providers: List<SearchProvider>) {

    companion object {
        /** The tab id of the built-in tab that interleaves every provider. */
        const val ALL_TAB_ID = "all"

        /**
         * The per-provider tie-break offset on the All tab: family *i* starts at `i * 1_000_000` so
         * equal scores keep providers in declaration order and one family's list would have to exceed
         * a million rows to bleed into the next.
         */
        private const val PROVIDER_TIE_BREAK_OFFSET = 1_000_000
    }

    /** Whether the popup is on screen. Drives both the full-window popup host and the shortcut toggle. */
    var isOpen by mutableStateOf(false)
        private set

    /** The current query, matched per keystroke (no debounce) so results stay live under fast typing. */
    var query by mutableStateOf("")
        private set

    /** The active tab id: [ALL_TAB_ID] or one of the providers' ids. */
    var tabId by mutableStateOf(ALL_TAB_ID)
        private set

    // ── Result navigation ──────────────────────────────────────────────────────────────────────────

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

    // ── Open / close / navigate ────────────────────────────────────────────────────────────────────

    /** Opens the popup. [tabId] pins the initial tab; otherwise [defaultTabId] decides it. */
    fun open(tabId: String? = null, defaultTabId: String = ALL_TAB_ID) {
        this.tabId = tabId ?: defaultTabId
        query = ""
        highlighted = 0
        isOpen = true
    }

    /** Toggles the popup (the app's search chord). */
    fun toggle(defaultTabId: String = ALL_TAB_ID) {
        if (isOpen) close() else open(defaultTabId = defaultTabId)
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

    fun selectTab(value: String) {
        tabId = value
        highlighted = 0
    }

    /** Steps the tab forward or back, wrapping at the ends (Tab / Shift+Tab, Ctrl+Down / Ctrl+Up). */
    fun cycleTab(delta: Int) {
        val tabs = tabIds()
        val current = tabs.indexOf(tabId).takeIf { it >= 0 } ?: 0
        tabId = tabs[(current + delta + tabs.size) % tabs.size]
        highlighted = 0
    }

    /** Every tab id in display order: the All tab first, then the providers. */
    fun tabIds(): List<String> = listOf(ALL_TAB_ID) + providers.map { it.id }

    /** The provider owning [id], or null for the All tab / a stale id. */
    fun provider(id: String): SearchProvider? = providers.firstOrNull { it.id == id }

    // ── Ranking ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The hits to show right now, for the current [tabId] and [query]. Called from composition (the
     * popup remembers it on query/tab changes), so state read inside the providers is tracked and a
     * change in any family's data re-ranks automatically.
     *
     * Empty-query behaviour follows the IDE: the All tab invites typing and shows nothing, a provider
     * tab shows whatever its [SearchProvider.candidates] decides is meaningful at rest. On the All tab
     * families are ranked as one list (each offset so ties keep provider order), then grouped by
     * provider — the order Up/Down walks matches the order that is drawn.
     */
    fun computeHits(strings: IslandUiStrings): List<SearchHit> {
        if (tabId == ALL_TAB_ID) {
            if (query.isBlank()) return emptyList()
            val all = providers.flatMapIndexed { index, provider ->
                provider.candidates(query, strings).map {
                    it.copy(tieBreakIndex = it.tieBreakIndex + index * PROVIDER_TIE_BREAK_OFFSET)
                }
            }
            // rank() orders by score+tieBreak (provider offset included); the stable sort then
            // buckets by provider so equal-ranked families stay grouped, in declaration order.
            return IslandSearchRanker.rank(all, query)
                .sortedBy { hit -> providers.indexOfFirst { it.id == hit.groupKey } }
        }
        val provider = provider(tabId) ?: return emptyList()
        return IslandSearchRanker.rank(provider.candidates(query, strings), query)
    }

    // ── Activation ─────────────────────────────────────────────────────────────────────────────────

    /** Runs the chosen result through its provider, then closes the popup. */
    fun activate(hit: SearchHit) {
        provider(hit.groupKey)?.onActivate(hit)
        close()
    }
}
