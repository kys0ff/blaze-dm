package org.blaze.engine.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.settings.EngineSettingsRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps at most `maxConcurrentDownloads` tasks running and turns queued ones into executor jobs.
 *
 * Everything that can change the "who is active" answer - queueing, starting, pausing, cancelling,
 * removing - is serialized through [queueMutex]. Without that, a pause landing between the moment
 * the scheduler flips a task to `Starting` and the moment its job is registered would leave no job
 * to cancel, and the download would silently take off right after the user paused it.
 */
class DownloadScheduler(
    private val scope: CoroutineScope,
    private val settingsRepository: EngineSettingsRepository,
    private val executorFactory: (DownloadTask) -> DownloadExecutor,
    private val onTaskUpdated: (DownloadTask) -> Unit
) {
    private val tasks = MutableStateFlow<Map<DownloadId, DownloadTask>>(emptyMap())
    private val jobs = ConcurrentHashMap<DownloadId, Job>()
    private val queueMutex = Mutex()

    private val allTasksFlow = tasks
        .map { it.values.toList().sortedByDescending { t -> t.createdAt } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            settingsRepository.settings.collect {
                processQueue()
            }
        }
        scope.launch {
            while (true) {
                delay(SCHEDULE_TICK.milliseconds)
                processQueue()
            }
        }
    }

    fun updateTasks(loadedTasks: Map<DownloadId, DownloadTask>) {
        tasks.value = loadedTasks
        scope.launch { processQueue() }
    }

    private suspend fun processQueue() = queueMutex.withLock { processQueueLocked() }

    private suspend fun processQueueLocked() {
        val currentTasks = tasks.value
        val maxConcurrent = settingsRepository.settings.value.maxConcurrentDownloads

        val activeCount = currentTasks.values.count { it.state.isActive || jobs.containsKey(it.id) }
        if (activeCount >= maxConcurrent) {
            // Over the limit (e.g. the setting just shrank): new work is not started, running
            // downloads are left alone rather than being interrupted mid-transfer.
            return
        }

        val now = Instant.now()
        val queuedTasks = currentTasks.values
            .filter { it.state == DownloadState.Queued && (it.scheduledAt == null || !it.scheduledAt.isAfter(now)) }
            .filter { !jobs.containsKey(it.id) }
            .sortedBy { it.createdAt }

        var slotsAvailable = maxConcurrent - activeCount
        for (task in queuedTasks) {
            if (slotsAvailable <= 0) break

            // Re-read: an earlier iteration (or a queued pause) may have moved it already.
            val fresh = tasks.value[task.id] ?: continue
            if (fresh.state != DownloadState.Queued) continue

            slotsAvailable--
            val starting = fresh.copy(state = DownloadState.Starting, error = null)
            tasks.update { it + (task.id to starting) }
            onTaskUpdated(starting)
            launchLocked(starting)
        }
    }

    /** Registers the job while the caller holds [queueMutex], so pause cannot slip in between. */
    private suspend fun launchLocked(task: DownloadTask) {
        val id = task.id
        jobs[id]?.cancelAndJoin()

        val executor = executorFactory(task)
        // Start lazily so the job is registered in `jobs` before its body (and its
        // completion/cleanup) can run. Without this, a task whose flow completes very
        // quickly could finish and clear its entry before `jobs[id] = job` executes,
        // leaking a stale entry that permanently occupies a concurrency slot.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                executor.execute().collect { updatedTask ->
                    tasks.update { it + (id to updatedTask) }
                    onTaskUpdated(updatedTask)
                }
            } catch (_: Exception) {
            } finally {
                // Only remove if it's still this job
                jobs.compute(id) { _, current ->
                    if (current == coroutineContext[Job]) null else current
                }
                scope.launch { processQueue() }
            }
        }
        jobs[id] = job
        job.start()
    }

    fun enqueue(task: DownloadTask) {
        tasks.update { it + (task.id to task) }
        scope.launch { processQueue() }
    }

    suspend fun start(id: DownloadId) = queueMutex.withLock {
        val task = tasks.value[id] ?: return@withLock
        tasks.update {
            it + (id to task.copy(state = DownloadState.Queued, scheduledAt = null, error = null, retryCount = 0))
        }
        processQueueLocked()
    }

    suspend fun pause(id: DownloadId) = queueMutex.withLock {
        stopJobLocked(id)
        val task = tasks.value[id] ?: return@withLock
        val updated = task.copy(state = DownloadState.Paused, downloadSpeed = 0)
        tasks.update { it + (id to updated) }
        onTaskUpdated(updated)
        processQueueLocked()
    }

    suspend fun cancel(id: DownloadId) = queueMutex.withLock {
        stopJobLocked(id)
        val task = tasks.value[id] ?: return@withLock
        val updated = task.copy(state = DownloadState.Cancelled, downloadSpeed = 0)
        tasks.update { it + (id to updated) }
        onTaskUpdated(updated)
        processQueueLocked()
    }

    suspend fun remove(id: DownloadId) = queueMutex.withLock {
        stopJobLocked(id)
        jobs.remove(id)
        tasks.update { it - id }
        processQueueLocked()
    }

    private suspend fun stopJobLocked(id: DownloadId) {
        jobs[id]?.cancelAndJoin()
        jobs.remove(id)
    }

    fun observeTask(id: DownloadId): Flow<DownloadTask> =
        tasks.mapNotNull { it[id] }.distinctUntilChanged()

    fun observeAllTasks(): StateFlow<List<DownloadTask>> = allTasksFlow

    suspend fun shutdown() {
        queueMutex.withLock {
            jobs.values.forEach { it.cancelAndJoin() }
            jobs.clear()
        }
    }

    private companion object {
        /** Granularity of the scheduled-start check; also the self-heal interval for the queue. */
        const val SCHEDULE_TICK = 1_000L
    }
}
