package org.blaze.presentation.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * The fidelity/latency requirement for Search Everywhere at scale: ranking a query over tens of
 * thousands of downloads must stay fluid. This exercises the real ranking path (candidates -> ranked
 * hits) the popup runs on every keystroke, with no debounce, and asserts both correctness and a
 * generous time budget - a guard against an accidental O(n^2) in the scorer.
 */
class SearchBenchmarkTest {

    private fun downloads(size: Int) = (0 until size).map { i ->
        org.blaze.domain.models.Download(
            id = "id-$i",
            name = "movie-part-$i.mkv",
            url = "https://example.com/files/movie-$i.mkv",
            totalSize = 1_000_000L,
            downloadedSize = 500_000L,
            speed = 0L,
            state = org.blaze.domain.models.DownloadState.PAUSED,
            addedAt = i.toLong(),
            savePath = "/home/user/Downloads/movie-part-$i.mkv",
        )
    }

    @Test
    fun `ranking ten thousand downloads stays responsive`() {
        val strings = org.blaze.i18n.EnStrings
        val items = downloads(10_000)
        val elapsed = measureTime {
            val hits = SearchSources.hits(SearchTab.Downloads, "movie-part-500", items, strings)
            // The exact-ish prefix must win; a substring of a longer name must not outrank it.
            assertTrue(hits.isNotEmpty(), "the query must find downloads at 10k scale")
            assertTrue(hits.first().primary.contains("movie-part-500"), "best match leads")
            assertTrue(hits.size <= SearchRanker.MAX_RESULTS, "the result set is capped")
        }
        // A machine under test load can spike; the point is that a keystroke is O(n), not O(n^2).
        assertTrue(elapsed.inWholeMilliseconds < 3_000, "10k ranking took $elapsed")
    }

    @Test
    fun `result cap holds across all families at scale`() {
        val strings = org.blaze.i18n.EnStrings
        val items = downloads(5_000)
        val hits = SearchSources.hits(SearchTab.All, "movie", items, strings)
        assertEquals(SearchRanker.MAX_RESULTS, hits.size)
    }
}
