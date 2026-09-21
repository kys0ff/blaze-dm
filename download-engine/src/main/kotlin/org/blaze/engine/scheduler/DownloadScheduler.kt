package org.blaze.engine.scheduler

import kotlinx.coroutines.CoroutineScope
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
import kotlin.time.Duration.Companion.seconds

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
                delay(1.seconds)
                processQueue()
            }
        }
    }

    fun updateTasks(loadedTasks: Map<DownloadId, DownloadTask>) {
        tasks.value = loadedTasks
        scope.launch { processQueue() }
    }

    private suspend fun processQueue() {
        queueMutex.withLock {
            val currentTasks = tasks.value
            val maxConcurrent = settingsRepository.settings.value.maxConcurrentDownloads

            val activeTasks = currentTasks.values.filter { it.state.isActive || jobs.containsKey(it.id) }
            val activeCount = activeTasks.size
            
            if (activeCount >= maxConcurrent) {
                // If we are over the limit (e.g. settings changed), we don't start new ones.
                // We could also stop some, but let's stick to not starting new ones for now.
                return
            }

            val now = Instant.now()
            val queuedTasks = currentTasks.values
                .filter { it.state == DownloadState.Queued && (it.scheduledAt == null || !it.scheduledAt.isAfter(now)) }
                .filter { !jobs.containsKey(it.id) } // Safety check
                .sortedBy { it.createdAt }

            var slotsAvailable = maxConcurrent - activeCount
            for (task in queuedTasks) {
                if (slotsAvailable <= 0) break
                
                // Double check if it's already starting
                if (tasks.value[task.id]?.state == DownloadState.Starting) continue
                
                slotsAvailable--

                val updated = task.copy(state = DownloadState.Starting)
                tasks.update { it + (task.id to updated) }
                onTaskUpdated(updated)

                scope.launch {
                    actuallyStart(task.id)
                }
            }
        }
    }

    private suspend fun actuallyStart(id: DownloadId) {
        val task = tasks.value[id] ?: return
        
        // Ensure only one job per task
        jobs[id]?.cancelAndJoin()

        val executor = executorFactory(task)
        val job = scope.launch {
            try {
                executor.execute().collect { updatedTask ->
                    tasks.update { it + (id to updatedTask) }
                    onTaskUpdated(updatedTask)
                }
            } catch (_: Exception) {
            } finally {
                jobs.remove(id)
                scope.launch { processQueue() }
            }
        }
        jobs[id] = job
    }

    fun enqueue(task: DownloadTask) {
        tasks.update { it + (task.id to task) }
        scope.launch { processQueue() }
    }

    suspend fun start(id: DownloadId) {
        val task = tasks.value[id] ?: return
        tasks.update { it + (id to task.copy(state = DownloadState.Queued, scheduledAt = null, error = null, retryCount = 0)) }
        processQueue()
    }

    suspend fun pause(id: DownloadId) {
        jobs[id]?.cancelAndJoin()
        val task = tasks.value[id] ?: return
        val updated = task.copy(state = DownloadState.Paused)
        tasks.update { it + (id to updated) }
        onTaskUpdated(updated)
        processQueue()
    }

    suspend fun cancel(id: DownloadId) {
        jobs[id]?.cancelAndJoin()
        val task = tasks.value[id] ?: return
        val updated = task.copy(state = DownloadState.Cancelled)
        tasks.update { it + (id to updated) }
        onTaskUpdated(updated)
        processQueue()
    }

    suspend fun remove(id: DownloadId) {
        jobs[id]?.cancelAndJoin()
        jobs.remove(id)
        tasks.update { it - id }
        processQueue()
    }

    fun observeTask(id: DownloadId): Flow<DownloadTask> =
        tasks.mapNotNull { it[id] }.distinctUntilChanged()

    fun observeAllTasks(): StateFlow<List<DownloadTask>> = allTasksFlow

    suspend fun shutdown() {
        jobs.values.forEach { it.cancelAndJoin() }
        jobs.clear()
    }
}
