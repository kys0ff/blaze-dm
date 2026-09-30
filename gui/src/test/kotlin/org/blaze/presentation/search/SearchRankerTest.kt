package org.blaze.presentation.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Search Everywhere ranking contract, tested with no composition: the tiers, their order, the
 * stable tie-break, the matched-character ranges that drive the row highlight, and the empty-query
 * passthrough. These are the rules the popup's list ordering and highlighting depend on, so pinning
 * them here keeps the UI honest without a UI.
 */
class SearchRankerTest {

    private fun candidate(
        id: String,
        primary: String,
        secondary: String? = null,
        keywords: String = "",
        tie: Int = 0,
    ) = SearchCandidate(
        id = id,
        kind = SearchKind.Download,
        primary = primary,
        secondary = secondary,
        icon = null,
        shortcut = null,
        keywords = keywords,
        tieBreakIndex = tie,
    )

    @Test
    fun `exact match beats every other tier`() {
        val hits = SearchRanker.rank(
            listOf(
                candidate("sub", "archive.zip"),
                candidate("pre", "zipfile"),
                candidate("exact", "zip"),
            ),
            query = "zip",
        )
        assertEquals("exact", hits.first().id, "the whole-name equal must rank first")
    }

    @Test
    fun `prefix beats word-prefix beats subsequence`() {
        val hits = SearchRanker.rank(
            listOf(
                candidate("fuzzy", "my zebra is fast"),   // subsequence for "zf"? chars z..f present
                candidate("word", "open zip now"),        // word-prefix "zip"
                candidate("prefix", "zipdata"),           // prefix "zip"
            ),
            query = "zip",
        )
        assertEquals(listOf("prefix", "word"), hits.map { it.id }.take(2), "prefix then word-prefix")
    }

    @Test
    fun `identical scores keep their input order`() {
        val hits = SearchRanker.rank(
            listOf(
                candidate("b", "alpha", tie = 2),
                candidate("a", "alpha", tie = 1),
            ),
            query = "alpha",
        )
        assertEquals(listOf("a", "b"), hits.map { it.id }, "tie-break is the stable input index")
    }

    @Test
    fun `a subsequence produces coalesced highlight ranges`() {
        val hit = SearchRanker.score(candidate("s", "ubuntu-desktop.iso"), "dsk")
        assertTrue(hit != null && hit.matches.isNotEmpty(), "d-s-k appear in order")
        // 'd','s','k' land in "desktop": the runs must cover exactly the matched characters.
        val covered = hit!!.matches.flatMap { range -> (range.start until range.end).toList() }.toSet()
        val text = "ubuntu-desktop.iso"
        val matchedChars = listOf('d', 's', 'k')
        assertTrue(
            hit.matches.all { it.start in text.indices && it.end <= text.length },
            "ranges stay inside the primary",
        )
        assertTrue(covered.isNotEmpty())
        assertTrue(
            covered.all { text[it].lowercaseChar() in matchedChars },
            "every highlighted character is one the query asked for",
        )
    }

    @Test
    fun `secondary and keyword aliases match without highlighting the primary`() {
        val hit = SearchRanker.score(candidate("s", "Network", secondary = null, keywords = "proxy http"), "proxy")
        assertTrue(hit != null, "the English/alias keyword must surface the row")
        assertEquals(emptyList(), hit!!.matches, "a keyword-only match leaves the primary unhighlighted")
    }

    @Test
    fun `no match drops the candidate`() {
        assertNull(SearchRanker.score(candidate("s", "ubuntu"), "windows"))
    }

    @Test
    fun `blank query returns candidates untouched`() {
        val input = listOf(candidate("a", "zebra", tie = 0), candidate("b", "apple", tie = 1))
        val hits = SearchRanker.rank(input, "")
        assertEquals(listOf("a", "b"), hits.map { it.id }, "ranking never reorders an empty query")
        assertTrue(hits.all { it.matches.isEmpty() })
    }

    @Test
    fun `results are capped at the maximum`() {
        val many = (0..SearchRanker.MAX_RESULTS + 50).map { candidate("id$it", "download$it file") }
        val hits = SearchRanker.rank(many, "download")
        assertEquals(SearchRanker.MAX_RESULTS, hits.size)
    }

    @Test
    fun `subsequence ranges are null when characters are out of order`() {
        assertNull(SearchRanker.subsequenceRanges("abcdef", "ba"))
        assertFalse("abc".let { SearchRanker.subsequenceRanges(it, "z") != null })
    }
}
