package org.island.ui.search

/**
 * The pure, in-memory ranking engine behind Search Everywhere.
 *
 * Ranking tiers, best first, follow the IDE's Search Everywhere: exact, then prefix, then word-prefix,
 * then a fuzzy subsequence. A tier that cannot apply is skipped; a candidate that matches no tier is
 * dropped. The returned [SearchHit]s carry the matched ranges so the row can highlight them, and are
 * ordered by score then by the candidate's own [SearchCandidate.tieBreakIndex] so the result is stable
 * across keystrokes rather than reshuffling identical scores.
 */
object IslandSearchRanker {

    /** Tier scores are spaced widely so a later tier can never out-rank an earlier one on length alone. */
    private const val EXACT = 0
    private const val PREFIX = 1_000
    private const val WORD_PREFIX = 2_000
    private const val SUBSEQUENCE = 3_000

    /** Hard cap on results per call, so a query over tens of thousands of items stays fluid. */
    const val MAX_RESULTS = 200

    /**
     * Scores [candidates] against [query] and returns the best [MAX_RESULTS], best first. A blank
     * query returns the candidates untouched in their own order (the caller decides what an empty-query
     * tab lists), so ranking never invents a "recent" ordering of its own.
     */
    fun rank(candidates: List<SearchCandidate>, query: String): List<SearchHit> {
        if (query.isBlank()) {
            return candidates.map { it.toUnmatchedHit() }
        }
        val hits = ArrayList<SearchHit>(candidates.size)
        for (candidate in candidates) {
            val scored = score(candidate, query) ?: continue
            hits += scored
        }
        hits.sortWith(compareBy({ it.score }, { it.tieBreakIndex }))
        return if (hits.size > MAX_RESULTS) hits.subList(0, MAX_RESULTS) else hits
    }

    /** Scores one [candidate], or null when [query] does not match it at any tier. */
    fun score(candidate: SearchCandidate, query: String): SearchHit? {
        val q = query.trim()
        if (q.isEmpty()) return candidate.toUnmatchedHit()
        val target = candidate.primary

        // 1. exact — the whole primary equals the query.
        if (target.equals(q, ignoreCase = true)) {
            return candidate.toHit(EXACT + target.length, listOf(MatchRange(0, target.length)))
        }
        // 2. prefix.
        if (target.startsWith(q, ignoreCase = true)) {
            return candidate.toHit(PREFIX + target.length, listOf(MatchRange(0, q.length)))
        }
        // 3. word-prefix — the query starts one of the primary's whitespace-separated words.
        run {
            var index = 0
            while (index <= target.length - q.length) {
                val found = target.indexOf(q, index, ignoreCase = true)
                if (found < 0) break
                val atWordStart = found == 0 || target[found - 1].isWhitespace()
                if (atWordStart) {
                    return candidate.toHit(
                        WORD_PREFIX + found + target.length,
                        listOf(MatchRange(found, found + q.length))
                    )
                }
                index = found + 1
            }
        }
        // 4. fuzzy subsequence — the query's characters appear in order anywhere in the primary.
        val ranges = subsequenceRanges(target, q)
        if (ranges != null) {
            // A tighter spread (fewer skipped characters) ranks better than a scattered one.
            val spread = (ranges.last().end - ranges.first().start) - q.length
            return candidate.toHit(SUBSEQUENCE + spread, ranges)
        }
        // Fall back to matching on keywords / secondary text so aliases ("proxy" → Network) surface,
        // without highlighting the primary (which does not literally contain the term).
        if (matchesHidden(candidate, q)) {
            return candidate.toHit(SUBSEQUENCE + target.length + 1, emptyList())
        }
        return null
    }

    /**
     * The ordered character ranges of [query] within [text] as a subsequence, or null when the
     * characters do not appear in order. Case-insensitive; each query character maps to the earliest
     * still-available text character so the ranges stay tight and monotonic.
     */
    fun subsequenceRanges(text: String, query: String): List<MatchRange>? {
        var cursor = 0
        val ranges = ArrayList<MatchRange>(query.length)
        for (element in query) {
            val found = text.indexOf(element, startIndex = cursor, ignoreCase = true)
            if (found < 0) return null
            // Coalesce adjacent characters into one run so the highlight reads as a phrase, not a dotted line.
            val last = ranges.lastOrNull()
            if (last != null && found == last.end) {
                ranges[ranges.lastIndex] = last.copy(end = found + 1)
            } else {
                ranges += MatchRange(found, found + 1)
            }
            cursor = found + 1
        }
        return ranges
    }

    private fun matchesHidden(candidate: SearchCandidate, query: String): Boolean =
        candidate.secondary?.contains(query, ignoreCase = true) == true ||
                candidate.keywords.contains(query, ignoreCase = true)

    private fun SearchCandidate.toHit(score: Int, matches: List<MatchRange>) = SearchHit(
        id = id,
        groupKey = groupKey,
        primary = primary,
        secondary = secondary,
        icon = icon,
        shortcut = shortcut,
        matches = matches,
        score = score,
        tieBreakIndex = tieBreakIndex,
    )

    private fun SearchCandidate.toUnmatchedHit() = SearchHit(
        id = id,
        groupKey = groupKey,
        primary = primary,
        secondary = secondary,
        icon = icon,
        shortcut = shortcut,
        matches = emptyList(),
        score = 0,
        tieBreakIndex = tieBreakIndex,
    )
}
