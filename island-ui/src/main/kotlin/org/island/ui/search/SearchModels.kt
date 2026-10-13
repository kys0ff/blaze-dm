package org.island.ui.search

import org.island.ui.text.IslandUiStrings

/**
 * A contiguous, highlighted run within a result's primary text — the matched characters. The list is
 * ordered and non-overlapping so the renderer can wrap each range in an accent span.
 */
data class MatchRange(val start: Int, val end: Int) {
    init {
        require(start <= end) { "match range start ($start) must not exceed end ($end)" }
    }

    val length: Int get() = end - start
}

/**
 * One row in the Search Everywhere results list. Pure data: the popup renders it, the providers build
 * it, and the ranking engine scores it. [icon] and [shortcut] are left as opaque [Any]/[String] so the
 * ranking layer stays free of a Jewel/Compose dependency and remains unit-testable in isolation.
 *
 * @param id stable identity of the underlying item (an entity id, a command name, a setting id).
 * @param groupKey the [SearchProvider.id] whose family this result belongs to; drives the section
 *   header on the All tab and routes [IslandSearchController.activate] back to the right provider.
 * @param score lower is better; ties are broken by [tieBreakIndex] so ordering is deterministic.
 */
data class SearchHit(
    val id: String,
    val groupKey: String,
    val primary: String,
    val secondary: String?,
    val icon: Any?,
    val shortcut: String?,
    val matches: List<MatchRange>,
    val score: Int,
    val tieBreakIndex: Int,
)

/**
 * A searchable candidate before it is scored. The engine matches against [primary] and, when the
 * caller opts in, [secondary] and [keywords] — only [primary] is highlighted back to the row.
 */
data class SearchCandidate(
    val id: String,
    val groupKey: String,
    val primary: String,
    val secondary: String?,
    val icon: Any?,
    val shortcut: String?,
    val keywords: String = "",
    val tieBreakIndex: Int,
)

/**
 * One family of searchable results the app offers (downloads, actions, settings — or whatever a new
 * app invents). The popup shows a tab per provider plus a built-in "All" tab that interleaves them.
 *
 * [candidates] is called at composition time with the live query, so implementations should read
 * app state through snapshot state (a Compose `mutableStateOf` list) rather than caching — the popup
 * recomputes whenever any state read inside changes. Every returned candidate must carry this
 * provider's [id] as its [SearchCandidate.groupKey].
 */
interface SearchProvider {
    /** Stable tab identifier; also the [SearchCandidate.groupKey] this provider's hits carry. */
    val id: String

    /** The provider's tab label in the popup's tab row. */
    fun tabLabel(strings: IslandUiStrings): String

    /** The section header drawn above this provider's group on the All tab. */
    fun sectionTitle(strings: IslandUiStrings): String

    /** The family's candidates for [query] (blank query = the tab's at-rest list; see contract notes). */
    fun candidates(query: String, strings: IslandUiStrings): List<SearchCandidate>

    /** Runs the chosen result (navigate, execute, focus …) — called just before the popup closes. */
    fun onActivate(hit: SearchHit)
}
