package org.blaze.engine.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.scheduler.DownloadScheduler
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the two scheduler-owned lifecycle states that used to be declared but never produced.
 *
 * [DownloadState.Pausing] names the real teardown window inside `pause()` (a torrent/DHT unwind takes
 * time), and [DownloadState.Resuming] names the boot of a task that already has bytes on disk - distinct
 * from a cold [DownloadState.Starting]. Both are observable purely through the `onTaskUpdated` stream the
 * scheduler emits, so the test drives the scheduler directly and reads the emitted state sequence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSchedulerStateTransitionTest {

    /** An executor that never reports, so the boot snapshot the scheduler writes stays the last word. */
    private val hangingFactory: (DownloadTask) -> DownloadExecutor = {
        object : DownloadExecutor {
            override fun execute(): Flow<DownloadTask> = flow { awaitCancellation() }
        }
    }

    private suspend fun newScheduler(
        scope: CoroutineScope,
        updates: ConcurrentLinkedQueue<DownloadTask>,
    ): DownloadScheduler {
        val storageDir = Files.createTempDirectory("blaze-state-transition").resolve("storage")
        Files.createDirectories(storageDir)
        val settingsRepo = EngineSettingsRepository(storageDir)
        settingsRepo.updateSettings { it.copy(maxConcurrentDownloads = 4) }
        return DownloadScheduler(scope, settingsRepo, hangingFactory) { updates.add(it) }
    }

    private fun task(state: DownloadState, downloaded: Long) = DownloadTask(
        id = DownloadId.generate(),
        name = "t",
        request = DownloadRequest.Http("t", "http://u", Path.of(".")),
        state = state,
        totalBytes = 1000,
        downloadedBytes = downloaded,
        downloadSpeed = 0,
    )

    @Test
    fun `pausing an active task emits Pausing before it settles on Paused`() = runTest {
        val updates = ConcurrentLinkedQueue<DownloadTask>()
        val scheduler = newScheduler(backgroundScope, updates)
        val live = task(DownloadState.Downloading, 500)
        scheduler.enqueue(live)

        scheduler.pause(live.id)

        val seq = updates.filter { it.id == live.id }.map { it.state }
        assertTrue(DownloadState.Pausing in seq, "expected a Pausing tick during teardown, got $seq")
        assertEquals(DownloadState.Paused, seq.last(), "the pause must still settle on Paused")
        assertTrue(
            seq.indexOf(DownloadState.Pausing) < seq.indexOf(DownloadState.Paused),
            "Pausing must precede Paused, got $seq",
        )
    }

    @Test
    fun `a task with bytes on disk boots as Resuming while an empty one boots as Starting`() = runTest {
        val updates = ConcurrentLinkedQueue<DownloadTask>()
        val scheduler = newScheduler(backgroundScope, updates)

        val partial = task(DownloadState.Paused, 500)
        scheduler.enqueue(partial)
        scheduler.start(partial.id)
        assertEquals(
            DownloadState.Resuming,
            updates.last { it.id == partial.id }.state,
            "continuing a partial transfer is Resuming, not a cold start",
        )

        val fresh = task(DownloadState.Paused, 0)
        scheduler.enqueue(fresh)
        scheduler.start(fresh.id)
        assertEquals(
            DownloadState.Starting,
            updates.last { it.id == fresh.id }.state,
            "nothing on disk means a fresh Starting, not a resume",
        )
    }
}
