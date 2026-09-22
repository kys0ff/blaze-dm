package org.blaze.presentation.application

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the taskbar aggregate: the mean progress of the in-flight queue, and the
 * "nothing active" case that must clear the bar.
 */
class AppTaskbarProgressTest {

    private fun download(
        state: DownloadState,
        progress: Float,
    ) = Download(
        id = state.name + progress,
        name = "file",
        url = "https://example.com/file",
        totalSize = 100L,
        downloadedSize = (progress * 100).toLong(),
        speed = 0L,
        state = state,
        addedAt = 0L,
        savePath = "/tmp/file",
        progress = progress,
    )

    @Test
    fun `returns null when nothing is in flight`() {
        val downloads = listOf(
            download(DownloadState.COMPLETED, 1f),
            download(DownloadState.PAUSED, 0.5f),
            download(DownloadState.FAILED, 0.2f),
        )
        assertNull(aggregateProgress(downloads))
    }

    @Test
    fun `returns null for an empty list`() {
        assertNull(aggregateProgress(emptyList()))
    }

    @Test
    fun `averages the downloading and queued items only`() {
        val downloads = listOf(
            download(DownloadState.DOWNLOADING, 0.5f),
            download(DownloadState.QUEUED, 0.1f),
            download(DownloadState.COMPLETED, 1f), // ignored
            download(DownloadState.PAUSED, 0.9f),  // ignored
        )
        // mean of 0.5 and 0.1
        assertEquals(0.3f, aggregateProgress(downloads)!!, 1e-4f)
    }

    @Test
    fun `single active download reports its own progress`() {
        val downloads = listOf(download(DownloadState.DOWNLOADING, 0.42f))
        assertEquals(0.42f, aggregateProgress(downloads)!!, 1e-4f)
    }
}
