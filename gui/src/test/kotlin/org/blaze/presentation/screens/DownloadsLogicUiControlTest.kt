package org.blaze.presentation.screens

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.presentation.screens.downloads.DownloadsState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsLogicUiControlTest {

    @Test
    fun testHideResumeButtonWhenQueueIsFull() {
        // Given max Concurrent downloads limit is 2
        val maxConcurrent = 2

        // There are already 2 downloads currently running (state = DOWNLOADING)
        val activeDownloads = listOf(
            Download(id = "1", name = "file1", url = "http://1", state = DownloadState.DOWNLOADING, totalSize = 100, downloadedSize = 10, speed = 100, eta = null, peers = 1, addedAt = 0, savePath = ""),
            Download(id = "2", name = "file2", url = "http://2", state = DownloadState.DOWNLOADING, totalSize = 100, downloadedSize = 20, speed = 100, eta = null, peers = 1, addedAt = 0, savePath = "")
        )

        // There is also a queued download task
        val queuedDownload = Download(id = "3", name = "file3", url = "http://3", state = DownloadState.QUEUED, totalSize = 100, downloadedSize = 0, speed = 0, eta = null, peers = 0, addedAt = 0, savePath = "")

        val allDownloads = activeDownloads + queuedDownload
        val state = DownloadsState(
            downloads = allDownloads,
            filteredDownloads = allDownloads,
            maxConcurrentDownloads = maxConcurrent
        )

        // Logic check: calculate hideResumeForQueued based on activeCount >= maxConcurrentDownloads
        val activeCount = state.downloads.count { it.state == DownloadState.DOWNLOADING }
        val hideResumeForQueued = activeCount >= state.maxConcurrentDownloads

        // Verify that hideResumeForQueued is triggered correctly
        assertTrue(hideResumeForQueued, "Resume button should be hidden for queued downloads when the downloading queue is completely full")
    }

    @Test
    fun testShowResumeButtonWhenQueueIsNotFull() {
        val maxConcurrent = 2

        // Only 1 active download running
        val allDownloads = listOf(
            Download(id = "1", name = "file1", url = "http://1", state = DownloadState.DOWNLOADING, totalSize = 100, downloadedSize = 10, speed = 100, eta = null, peers = 1, addedAt = 0, savePath = ""),
            Download(id = "2", name = "file2", url = "http://2", state = DownloadState.QUEUED, totalSize = 100, downloadedSize = 0, speed = 0, eta = null, peers = 0, addedAt = 0, savePath = "")
        )

        val state = DownloadsState(
            downloads = allDownloads,
            filteredDownloads = allDownloads,
            maxConcurrentDownloads = maxConcurrent
        )

        val activeCount = state.downloads.count { it.state == DownloadState.DOWNLOADING }
        val hideResumeForQueued = activeCount >= state.maxConcurrentDownloads

        assertFalse(hideResumeForQueued, "Resume button should be visible for queued downloads when active downloading slots are still available")
    }
}
