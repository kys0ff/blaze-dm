package org.blaze.engine.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression test for the start/pause race: a pause arriving after the scheduler had flipped a task
 * to `Starting` but before its job was registered found no job to cancel, and the "paused" download
 * kept streaming.
 *
 * Queue mutations now all run under one mutex, so a pause is either entirely before the launch or
 * entirely after it. The loop exists because a race that never happens on the first attempt is not
 * a tested race.
 */
class DownloadSchedulerPauseTest {

    private val tempDir: Path = Files.createTempDirectory("blaze-pause-race")
    private val storageDir = tempDir.resolve("storage").also { Files.createDirectories(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Streams forever until cancelled, and remembers whether it is still doing that. */
    private class RacingExecutor(private val task: DownloadTask) : DownloadExecutor {
        @Volatile
        var running = false

        override fun execute(): Flow<DownloadTask> = channelFlow {
            running = true
            try {
                var downloaded = 0L
                while (true) {
                    delay(10.milliseconds)
                    downloaded += 10
                    send(
                        task.copy(
                            state = DownloadState.Downloading,
                            downloadedBytes = downloaded,
                            totalBytes = 1_000_000,
                            downloadSpeed = 1_000
                        )
                    )
                }
            } finally {
                running = false
            }
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a pause arriving during startup cannot leave the download running`(): Unit = runBlocking {
        val settingsRepository = EngineSettingsRepository(storageDir)
        settingsRepository.updateSettings { it.copy(maxConcurrentDownloads = 1, autoRetryFailed = false) }

        val started = ConcurrentLinkedQueue<RacingExecutor>()
        val manager = DownloadManager(
            scope = scope,
            repository = DownloadRepository(storageDir),
            settingsRepository = settingsRepository,
            executorFactory = { task -> RacingExecutor(task).also { started += it } }
        )

        val id = manager.enqueue(
            DownloadRequest.Http("race", "http://example.invalid", tempDir.resolve("out.bin"))
        )

        repeat(ATTEMPTS) { attempt ->
            manager.start(id)
            manager.pause(id)
            withContext(Dispatchers.Default) { delay(SETTLE.milliseconds) }

            assertEquals(DownloadState.Paused, manager.getTask(id)?.state, "pause lost the race on attempt $attempt")
            assertEquals(0L, manager.getTask(id)?.downloadSpeed, "a paused task still reports a speed")
        }

        assertTrue(started.isNotEmpty(), "no executor was ever created; the test would prove nothing")
        assertFalse(started.any { it.running }, "an executor kept streaming after its task was paused")

        manager.shutdown()
    }

    private companion object {
        const val ATTEMPTS = 25
        const val SETTLE = 80L
    }
}
