package org.blaze.presentation.screens.downloads.components

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.presentation.screens.downloads.toCounts
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scope a "clear completed" confirmation is allowed to promise.
 *
 * Guarded here: clearing drops every finished row, and a torrent that is still seeding *is* finished,
 * so a count built from COMPLETED alone would understate exactly what the user is confirming.
 */
class ClearCompletedScopeTest {

    private fun download(id: String, state: DownloadState) = Download(
        id = id,
        name = "$id.bin",
        url = "https://example.com/$id.bin",
        totalSize = 1_000,
        downloadedSize = 1_000,
        speed = 0,
        state = state,
        addedAt = 0L,
        savePath = "/home/user/Downloads/$id.bin",
    )

    @Test
    fun `seeding rows are part of the cleared scope`() {
        val counts = listOf(
            download("done", DownloadState.COMPLETED),
            download("seed", DownloadState.SEEDING),
            download("moving", DownloadState.DOWNLOADING),
            download("broken", DownloadState.FAILED),
        ).toCounts()

        val scope = ClearCompletedScope(counts.completed, counts.seeding)

        assertEquals(1, scope.completed)
        assertEquals(1, scope.seeding)
        assertEquals(2, scope.total)
    }

    @Test
    fun `unfinished rows never join the cleared scope`() {
        val counts = listOf(
            download("moving", DownloadState.DOWNLOADING),
            download("halted", DownloadState.PAUSED),
            download("waiting", DownloadState.QUEUED),
            download("broken", DownloadState.FAILED),
        ).toCounts()

        assertEquals(0, ClearCompletedScope(counts.completed, counts.seeding).total)
    }
}
