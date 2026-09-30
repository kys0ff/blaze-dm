package org.blaze.presentation.search

import org.blaze.domain.models.Download
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.EnStrings
import org.blaze.presentation.screens.settings.SettingsRegistry
import org.blaze.presentation.screens.downloads.components.label
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Turns the app's three real data families — the download list, the [AppActions] command registry and
 * the [SettingsRegistry] — into the ranked [SearchHit]s the Search Everywhere popup shows.
 *
 * Everything here is pure and takes its inputs explicitly ([downloads], [strings]), so it can be unit
 * tested (and benchmarked at 10k downloads) without a composition. Nothing is invented: the Downloads
 * tab mirrors the on-screen list, the Actions tab mirrors the hamburger menu, and the Settings tab
 * mirrors the settings registry, all fed through the one [SearchRanker].
 *
 * Settings results search *both* the current-language label (shown and highlighted) and an English
 * alias plus curated keywords, so an Arabic UI still finds "proxy" under الشبكة, and vice versa.
 */
object SearchSources {

    /** Downloads shown when the Downloads tab is opened with an empty query: the list's most recent rows. */
    const val BLANK_DOWNLOAD_LIMIT = 10

    fun downloadCandidates(downloads: List<Download>, strings: BlazeStrings): List<SearchCandidate> =
        downloads.mapIndexed { index, download ->
            SearchCandidate(
                id = download.id,
                kind = SearchKind.Download,
                primary = download.name,
                secondary = download.state.label(strings),
                icon = download.state,
                shortcut = null,
                keywords = "${download.url} ${download.savePath}",
                tieBreakIndex = index,
            )
        }

    fun actionCandidates(strings: BlazeStrings): List<SearchCandidate> =
        AppActions.searchable.mapIndexed { index, action ->
            SearchCandidate(
                id = action.command.name,
                kind = SearchKind.Action,
                primary = action.label(strings),
                secondary = null,
                icon = action.icon,
                shortcut = AppActions.accelerator(action),
                keywords = "",
                tieBreakIndex = index,
            )
        }

    fun settingCandidates(strings: BlazeStrings): List<SearchCandidate> =
        SettingsRegistry.options.mapIndexed { index, option ->
            // English aliases: match against the English title/description even when the UI is another
            // language, layered with the registry's curated synonyms.
            val englishTitle = option.title(EnStrings)
            val englishDesc = option.description?.invoke(EnStrings).orEmpty()
            val keywords = (listOf(englishTitle, englishDesc) + option.keywords)
                .filter { it.isNotBlank() }
                .joinToString(separator = " ")
            SearchCandidate(
                id = option.id,
                kind = SearchKind.Setting,
                primary = option.title(strings),
                secondary = SettingsRegistry.categoryTitle(option.category, strings),
                icon = AllIconsKeys.General.Settings,
                shortcut = null,
                keywords = keywords,
                tieBreakIndex = index,
            )
        }

    /**
     * The ranked hits for [tab] and [query]. The empty-query behaviour follows the IDE: the All tab
     * invites the user to type, the Downloads tab lists the most recent transfers, and the Actions and
     * Settings tabs list everything they can offer (all their entries are meaningful at rest).
     */
    fun hits(
        tab: SearchTab,
        query: String,
        downloads: List<Download>,
        strings: BlazeStrings,
    ): List<SearchHit> {
        val blank = query.isBlank()
        val candidates: List<SearchCandidate> = when (tab) {
            SearchTab.All -> if (blank) emptyList() else allCandidates(downloads, strings)
            SearchTab.Downloads -> {
                val source = if (blank) downloads.take(BLANK_DOWNLOAD_LIMIT) else downloads
                downloadCandidates(source, strings)
            }
            SearchTab.Actions -> actionCandidates(strings)
            SearchTab.Settings -> settingCandidates(strings)
        }
        return SearchRanker.rank(candidates, query)
    }

    /** Every kind interleaved, for the All tab, each family keeping its own internal order. */
    fun allCandidates(downloads: List<Download>, strings: BlazeStrings): List<SearchCandidate> {
        val downloadsPart = downloadCandidates(downloads, strings)
        val actionsPart = actionCandidates(strings).map { it.copy(tieBreakIndex = it.tieBreakIndex + 1_000_000) }
        val settingsPart = settingCandidates(strings).map { it.copy(tieBreakIndex = it.tieBreakIndex + 2_000_000) }
        return downloadsPart + actionsPart + settingsPart
    }
}
