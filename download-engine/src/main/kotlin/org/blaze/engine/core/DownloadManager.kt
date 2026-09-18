package org.blaze.engine.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.persistence.DownloadRecord
import org.blaze.engine.persistence.DownloadRepository
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class DownloadManager(
    private val scope: CoroutineScope,
    private val repository: DownloadRepository,
    private val httpDownloaderFactory: (DownloadRequest.Http) -> Downloader,
    private val torrentDownloaderFactory: (DownloadRequest.Torrent) -> Downloader
) : DownloadEngine {

    private val tasks = MutableStateFlow<Map<DownloadId, DownloadTask>>(emptyMap())
    private val jobs = ConcurrentHashMap<DownloadId, Job>()
    private val downloaders = ConcurrentHashMap<DownloadId, Downloader>()
    
    private val allTasksFlow = tasks
        .map { it.values.toList().sortedByDescending { t -> t.createdAt } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    
    init {
        loadTasks()
    }

    private fun loadTasks() {
        scope.launch(Dispatchers.IO) {
            val records = try {
                repository.loadAll()
            } catch (e: Exception) {
                println("Failed to load tasks: ${e.message}")
                emptyList()
            }
            val loadedTasks = records.map { record ->
                val request = when (record.type) {
                    "HTTP" -> DownloadRequest.Http(record.name, record.url!!, Path.of(record.destination))
                    "TORRENT" -> {
                        val source = if (record.magnetUri != null) {
                            TorrentSource.Magnet(record.magnetUri)
                        } else {
                            TorrentSource.File(Path.of(record.torrentPath!!))
                        }
                        DownloadRequest.Torrent(record.name, source, Path.of(record.destination))
                    }
                    else -> throw IllegalArgumentException("Unknown type")
                }
                
                val state = when (record.state) {
                    "COMPLETED" -> DownloadState.Completed
                    "PAUSED" -> DownloadState.Paused
                    "FAILED" -> DownloadState.Failed
                    else -> DownloadState.Queued
                }

                DownloadTask(
                    id = DownloadId(record.id),
                    name = record.name,
                    request = request,
                    state = state,
                    totalBytes = record.totalBytes,
                    downloadedBytes = record.downloadedBytes,
                    downloadSpeed = 0,
                    createdAt = Instant.ofEpochMilli(record.addedAt)
                )
            }
            tasks.update { loadedTasks.associateBy { it.id } }
        }
    }

    private fun persist() {
        val currentTasks = tasks.value.values.toList()
        scope.launch(Dispatchers.IO) {
            val records = currentTasks.map { task ->
                DownloadRecord(
                    id = task.id.value,
                    name = task.name,
                    type = when (task.request) {
                        is DownloadRequest.Http -> "HTTP"
                        is DownloadRequest.Torrent -> "TORRENT"
                    },
                    url = (task.request as? DownloadRequest.Http)?.url,
                    torrentPath = ((task.request as? DownloadRequest.Torrent)?.torrentSource as? TorrentSource.File)?.path?.toString(),
                    magnetUri = ((task.request as? DownloadRequest.Torrent)?.torrentSource as? TorrentSource.Magnet)?.uri,
                    destination = task.request.destination.toString(),
                    state = task.state.toString(),
                    totalBytes = task.totalBytes,
                    downloadedBytes = task.downloadedBytes,
                    addedAt = task.createdAt.toEpochMilli()
                )
            }
            repository.saveAll(records)
        }
    }

    override suspend fun enqueue(request: DownloadRequest): DownloadId {
        println("Enqueuing download: ${request.name} -> ${request.destination}")
        val id = DownloadId.generate()
        val task = DownloadTask(
            id = id,
            name = request.name,
            request = request,
            state = DownloadState.Queued,
            totalBytes = null,
            downloadedBytes = 0,
            downloadSpeed = 0
        )
        tasks.update { it + (id to task) }
        persist()
        return id
    }

    override suspend fun start(id: DownloadId) {
        val task = tasks.value[id] ?: return
        println("Starting download: ${task.name} (id=${id.value})")
        if (task.state == DownloadState.Downloading || task.state == DownloadState.Starting) return

        // Cancel existing job if any (shouldn't happen with the state check but just in case)
        jobs[id]?.cancel()

        val downloader = when (val request = task.request) {
            is DownloadRequest.Http -> httpDownloaderFactory(request)
            is DownloadRequest.Torrent -> torrentDownloaderFactory(request)
        }
        
        downloaders[id] = downloader
        
        val job = scope.launch {
            try {
                downloader.download().collect { updatedTask ->
                    tasks.update { it + (id to updatedTask.copy(id = id, request = task.request)) }
                    if (updatedTask.state == DownloadState.Completed || updatedTask.state == DownloadState.Failed) {
                        persist()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    println("Error in download collection for ${task.name}: ${e.message}")
                    tasks.update { it + (id to it[id]!!.copy(state = DownloadState.Failed)) }
                }
            }
        }
        jobs[id] = job
    }

    override suspend fun pause(id: DownloadId) {
        val downloader = downloaders[id] ?: return
        downloader.pause()
        jobs[id]?.cancel()
        tasks.update { it + (id to it[id]!!.copy(state = DownloadState.Paused)) }
        persist()
    }

    override suspend fun resume(id: DownloadId) {
        start(id)
    }

    override suspend fun cancel(id: DownloadId) {
        jobs[id]?.cancel()
        downloaders[id]?.cancel()
        tasks.update { it + (id to it[id]!!.copy(state = DownloadState.Cancelled)) }
        persist()
    }

    override suspend fun retry(id: DownloadId) {
        start(id)
    }

    override suspend fun remove(id: DownloadId, deleteFiles: Boolean) {
        cancel(id)
        val task = tasks.value[id]
        if (deleteFiles && task != null) {
            task.request.destination.toFile().delete()
            task.request.destination.resolveSibling("${task.request.destination.fileName}.part").toFile().delete()
        }
        tasks.update { it - id }
        downloaders.remove(id)
        jobs.remove(id)
        persist()
    }

    override suspend fun getTask(id: DownloadId): DownloadTask? = tasks.value[id]

    override fun observeTask(id: DownloadId): Flow<DownloadTask> = 
        tasks.mapNotNull { it[id] }.distinctUntilChanged()

    override fun observeAllTasks(): StateFlow<List<DownloadTask>> = allTasksFlow

    override suspend fun shutdown() {
        downloaders.values.forEach { 
            try { it.pause() } catch (_: Exception) { }
        }
        jobs.values.forEach { it.cancel() }
        persist()
        scope.cancel()
    }
}

interface Downloader {
    fun download(): Flow<DownloadTask>
    suspend fun pause()
    suspend fun cancel()
}
