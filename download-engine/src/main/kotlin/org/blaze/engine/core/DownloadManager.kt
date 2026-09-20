package org.blaze.engine.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadMetadata
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.persistence.DownloadRecord
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DownloadManager(
    private val scope: CoroutineScope,
    private val repository: DownloadRepository,
    private val httpDownloaderFactory: (DownloadRequest.Http) -> Downloader,
    private val torrentDownloaderFactory: (DownloadRequest.Torrent, onMetadata: (ByteArray) -> Unit) -> Downloader,
    private val settingsRepository: EngineSettingsRepository
) : DownloadEngine {

    private val logger = LoggerFactory.getLogger(DownloadManager::class.java)

    private val tasks = MutableStateFlow<Map<DownloadId, DownloadTask>>(emptyMap())
    private val jobs = ConcurrentHashMap<DownloadId, Job>()
    private val downloaders = ConcurrentHashMap<DownloadId, Downloader>()
    private val retryCounts = ConcurrentHashMap<DownloadId, Int>()
    private val queueMutex = Mutex()

    private val allTasksFlow = tasks
        .map { it.values.toList().sortedByDescending { t -> t.createdAt } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val persistSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            try {
                Files.createDirectories(repository.storageDir.resolve("cache"))
            } catch (e: Exception) {
                logger.error("[DownloadManager] Failed to create cache directory: ${e.message}")
            }
            persistSignal.consumeEach {
                writeSnapshot()
            }
        }
        loadTasks()

        scope.launch {
            settingsRepository.settings.collect {
                processQueue()
            }
        }
    }

    private fun loadTasks() {
        scope.launch(Dispatchers.IO) {
            val records = try {
                repository.loadAll()
            } catch (e: Exception) {
                logger.error("Failed to load tasks: ${e.message}")
                emptyList()
            }

            val settings = settingsRepository.settings.value
            val loadedTasks = records.mapNotNull { record ->
                try {
                    val task = parseRecord(record)
                    val finalState = if (task.state == DownloadState.Queued) {
                        val wasActive = record.state.uppercase() !in listOf("COMPLETED", "PAUSED", "FAILED", "CANCELLED", "SEEDING", "QUEUED")
                        if (wasActive) {
                            if (settings.resumeDownloadsOnStartup) DownloadState.Queued else DownloadState.Paused
                        } else {
                            if (settings.startQueuedOnStartup) DownloadState.Queued else DownloadState.Paused
                        }
                    } else {
                        task.state
                    }
                    task.copy(state = finalState)
                } catch (e: Exception) {
                    logger.warn("Skipping corrupt record ${record.id}: ${e.message}")
                    null
                }
            }

            tasks.update { current -> loadedTasks.associateBy { it.id } + current }
            processQueue()
        }
    }

    private fun parseRecord(record: DownloadRecord): DownloadTask {
        val request = when (record.type) {
            "HTTP" -> DownloadRequest.Http(
                record.name,
                record.url ?: error("HTTP record ${record.id} missing url"),
                Path.of(record.destination)
            )
            "TORRENT" -> {
                val source = when {
                    record.torrentPath != null -> TorrentSource.File(Path.of(record.torrentPath))
                    record.magnetUri != null -> TorrentSource.Magnet(record.magnetUri)
                    else -> error("Torrent record ${record.id} missing both torrentPath and magnetUri")
                }
                DownloadRequest.Torrent(record.name, source, Path.of(record.destination))
            }
            else -> error("Unknown download type '${record.type}' for record ${record.id}")
        }

        return DownloadTask(
            id = DownloadId(record.id),
            name = record.name,
            request = request,
            state = deserializeState(record.state),
            totalBytes = record.totalBytes,
            downloadedBytes = record.downloadedBytes,
            downloadSpeed = 0,
            createdAt = Instant.ofEpochMilli(record.addedAt)
        )
    }

    private fun deserializeState(raw: String): DownloadState = when (raw.uppercase()) {
        "COMPLETED" -> DownloadState.Completed
        "PAUSED" -> DownloadState.Paused
        "FAILED" -> DownloadState.Failed
        "CANCELLED" -> DownloadState.Cancelled
        "SEEDING" -> DownloadState.Seeding
        else -> DownloadState.Queued
    }

    private fun serializeState(state: DownloadState): String =
        state::class.simpleName?.uppercase() ?: "QUEUED"

    private fun persist() {
        persistSignal.trySend(Unit)
    }

    private fun cacheMetadata(id: DownloadId, bytes: ByteArray) {
        logger.debug("[DownloadManager] Attempting to cache metadata for $id (${bytes.size} bytes)")
        scope.launch(Dispatchers.IO) {
            val task = tasks.value[id] ?: return@launch
            val currentRequest = task.request as? DownloadRequest.Torrent ?: return@launch

            val isFile = currentRequest.torrentSource is TorrentSource.File
            if (isFile) {
                val path = (currentRequest.torrentSource as TorrentSource.File).path
                val cachePath = repository.storageDir.resolve("cache")
                val isTemp = path.toString().contains(System.getProperty("java.io.tmpdir"))
                val isCache = path.startsWith(cachePath)

                if (!isTemp && !isCache) {
                    logger.info("[DownloadManager] Source is a permanent file, skipping cache: $path")
                    return@launch
                }
                
                if (isCache && Files.exists(path)) {
                    logger.info("[DownloadManager] Source is already in cache, skipping redundant write: $path")
                    return@launch
                }
            }

            try {
                val cacheDir = repository.storageDir.resolve("cache")
                logger.debug("[DownloadManager] Ensuring cache directory exists: ${cacheDir.toAbsolutePath()}")
                Files.createDirectories(cacheDir)

                val torrentFile = cacheDir.resolve("${id.value}.torrent")
                Files.write(torrentFile, bytes)

                logger.info("[DownloadManager] Cached metadata for ${task.name} to $torrentFile")

                tasks.update { current ->
                    val t = current[id] ?: return@update current
                    val r = t.request as? DownloadRequest.Torrent ?: return@update current
                    val newRequest = r.copy(torrentSource = TorrentSource.File(torrentFile))
                    current + (id to t.copy(request = newRequest))
                }
                persist()
            } catch (e: Exception) {
                logger.error("[DownloadManager] Failed to cache metadata for ${task.name}: ${e.message}")
            }
        }
    }

    private fun cleanupMetadata(id: DownloadId) {
        scope.launch(Dispatchers.IO) {
            try {
                val cacheDir = repository.storageDir.resolve("cache")
                val torrentFile = cacheDir.resolve("${id.value}.torrent")
                if (Files.exists(torrentFile)) {
                    Files.delete(torrentFile)
                    logger.info("[DownloadManager] Cleaned up cached metadata for $id")
                }
            } catch (e: Exception) {
                logger.error("[DownloadManager] Failed to cleanup metadata for $id: ${e.message}")
            }
        }
    }

    private suspend fun writeSnapshot() {
        val currentTasks = tasks.value.values.toList()
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
                state = serializeState(task.state),
                totalBytes = task.totalBytes,
                downloadedBytes = task.downloadedBytes,
                addedAt = task.createdAt.toEpochMilli()
            )
        }
        try {
            withContext(Dispatchers.IO) {
                repository.saveAll(records)
            }
        } catch (e: Exception) {
            logger.error("Failed to persist tasks: ${e.message}")
        }
    }

    private fun isStateActive(state: DownloadState): Boolean = when (state) {
        DownloadState.Downloading,
        DownloadState.Starting,
        DownloadState.Resuming,
        DownloadState.Verifying,
        DownloadState.ResolvingMetadata -> true
        else -> false
    }

    private suspend fun processQueue() {
        queueMutex.withLock {
            val currentTasks = tasks.value
            val maxConcurrent = settingsRepository.settings.value.maxConcurrentDownloads

            val activeCount = currentTasks.values.count { isStateActive(it.state) }
            if (activeCount >= maxConcurrent) return@withLock

            val queuedTasks = currentTasks.values
                .filter { it.state == DownloadState.Queued }
                .sortedBy { it.createdAt }

            var slotsAvailable = maxConcurrent - activeCount
            for (task in queuedTasks) {
                if (slotsAvailable <= 0) break
                slotsAvailable--

                tasks.update { current ->
                    current + (task.id to task.copy(state = DownloadState.Starting))
                }
                persist()

                scope.launch {
                    actuallyStart(task.id)
                }
            }
        }
    }

    private suspend fun actuallyStart(id: DownloadId) {
        val task = tasks.value[id] ?: return

        jobs[id]?.cancelAndJoin()
        downloaders.remove(id)?.let { old ->
            try {
                old.cancel()
            } catch (_: Exception) {
            }
        }

        val downloader = when (val request = task.request) {
            is DownloadRequest.Http -> httpDownloaderFactory(request)
            is DownloadRequest.Torrent -> torrentDownloaderFactory(request) { bytes ->
                cacheMetadata(id, bytes)
            }
        }

        downloaders[id] = downloader

        var metadataPersisted = task.totalBytes != null

        val job = scope.launch {
            try {
                downloader.download().collect { updatedTask ->
                    tasks.update { current ->
                        if (current.containsKey(id)) {
                            val previousTask = current[id]
                            val transientState = updatedTask.state == DownloadState.Starting ||
                                    updatedTask.state == DownloadState.ResolvingMetadata ||
                                    updatedTask.state == DownloadState.Verifying

                            val finalTask = if (transientState && previousTask != null) {
                                updatedTask.copy(
                                    id = id,
                                    request = task.request,
                                    downloadedBytes = if (updatedTask.downloadedBytes == 0L) {
                                        previousTask.downloadedBytes
                                    } else {
                                        updatedTask.downloadedBytes
                                    },
                                    totalBytes = updatedTask.totalBytes ?: previousTask.totalBytes,
                                    progress = if (updatedTask.progress == null || updatedTask.progress == 0f) {
                                        previousTask.progress
                                    } else {
                                        updatedTask.progress
                                    }
                                )
                            } else {
                                updatedTask.copy(id = id, request = task.request)
                            }
                            current + (id to finalTask)
                        } else {
                            current
                        }
                    }
                    when {
                        updatedTask.state == DownloadState.Completed -> {
                            cleanupMetadata(id)
                            persist()
                            scope.launch { processQueue() }
                        }
                        updatedTask.state == DownloadState.Failed -> {
                            persist()
                            handleFailure(id)
                        }
                        !metadataPersisted && updatedTask.totalBytes != null -> {
                            metadataPersisted = true
                            persist()
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("Error in download collection for ${task.name}: ${e.message}")
                    tasks.update { current ->
                        current[id]?.let { current + (id to it.copy(state = DownloadState.Failed)) } ?: current
                    }
                    persist()
                    handleFailure(id)
                }
            }
        }
        jobs[id] = job
    }

    private fun handleFailure(id: DownloadId) {
        val settings = settingsRepository.settings.value
        if (settings.autoRetryFailed) {
            val currentRetries = retryCounts.getOrDefault(id, 0)
            if (currentRetries < settings.maxRetries) {
                retryCounts[id] = currentRetries + 1
                logger.info("Scheduling automatic retry for download $id (${currentRetries + 1}/${settings.maxRetries}) in ${settings.retryDelaySeconds}s")

                tasks.update { current ->
                    current[id]?.let { current + (id to it.copy(state = DownloadState.Queued)) } ?: current
                }
                persist()

                scope.launch {
                    delay((settings.retryDelaySeconds.toLong() * 1000).milliseconds)
                    if (tasks.value[id]?.state == DownloadState.Queued) {
                        processQueue()
                    }
                }
                return
            }
        }
        scope.launch { processQueue() }
    }

    override suspend fun fetchMetadata(request: DownloadRequest): DownloadMetadata? = withContext(Dispatchers.IO) {
        val downloader = when (request) {
            is DownloadRequest.Http -> httpDownloaderFactory(request)
            is DownloadRequest.Torrent -> torrentDownloaderFactory(request) { /* no-op for pre-fetch */ }
        }

        val task = withTimeoutOrNull(30.seconds) {
            downloader.download().firstOrNull { it.totalBytes != null && it.totalBytes > 0 }
        }

        try {
            downloader.cancel()
        } catch (_: Exception) {
        }

        task?.let { DownloadMetadata(it.name, it.totalBytes) }
    }

    override suspend fun enqueue(request: DownloadRequest): DownloadId {
        logger.info("Enqueuing download: ${request.name} -> ${request.destination}")
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
        retryCounts.remove(id)
        tasks.update { current ->
            current[id]?.let { current + (id to it.copy(state = DownloadState.Queued, error = null)) } ?: current
        }
        persist()
        processQueue()
    }

    override suspend fun pause(id: DownloadId) {
        retryCounts.remove(id)
        val downloader = downloaders[id]
        if (downloader != null) {
            try {
                downloader.pause()
            } catch (_: Exception) {
            }
        }
        jobs[id]?.cancelAndJoin()
        tasks.update { current ->
            current[id]?.let { current + (id to it.copy(state = DownloadState.Paused)) } ?: current
        }
        persist()
        processQueue()
    }

    override suspend fun resume(id: DownloadId) {
        start(id)
    }

    override suspend fun cancel(id: DownloadId) {
        retryCounts.remove(id)
        jobs[id]?.cancelAndJoin()
        val downloader = downloaders[id]
        if (downloader != null) {
            try {
                downloader.cancel()
            } catch (_: Exception) {
            }
        }
        tasks.update { current ->
            current[id]?.let { current + (id to it.copy(state = DownloadState.Cancelled)) } ?: current
        }
        persist()
        processQueue()
    }

    override suspend fun retry(id: DownloadId) {
        start(id)
    }

    override suspend fun remove(id: DownloadId, deleteFiles: Boolean) {
        val task = tasks.value[id]
        cancel(id)
        cleanupMetadata(id)

        if (deleteFiles && task != null) {
            try {
                val destination = task.request.destination.toFile()
                if (task.request is DownloadRequest.Torrent) {
                    val torrentFileOrDir = File(destination, task.name)
                    if (torrentFileOrDir.exists()) {
                        if (torrentFileOrDir.isDirectory) {
                            torrentFileOrDir.deleteRecursively()
                        } else {
                            torrentFileOrDir.delete()
                        }
                    }
                    if (destination.name == task.name) {
                        destination.deleteRecursively()
                    } else if (destination.isDirectory && destination.list()?.isEmpty() == true) {
                        if (destination.name == "download") {
                            destination.delete()
                        }
                    }
                } else {
                    if (destination.isDirectory) {
                        destination.deleteRecursively()
                    } else {
                        destination.delete()
                        destination.resolveSibling("${destination.name}.part").delete()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to delete files for ${task.name}: ${e.message}")
            }
        }

        tasks.update { it - id }
        downloaders.remove(id)
        jobs.remove(id)
        persist()
        processQueue()
    }

    override suspend fun getTask(id: DownloadId): DownloadTask? = tasks.value[id]

    override fun observeTask(id: DownloadId): Flow<DownloadTask> =
        tasks.mapNotNull { it[id] }.distinctUntilChanged()

    override fun observeAllTasks(): StateFlow<List<DownloadTask>> = allTasksFlow

    override suspend fun shutdown() {
        jobs.values.forEach { it.cancelAndJoin() }
        downloaders.values.forEach {
            try {
                it.pause()
            } catch (_: Exception) {
            }
        }
        writeSnapshot()
        persistSignal.close()
        scope.cancel()
    }
}

interface Downloader {
    fun download(): Flow<DownloadTask>
    suspend fun pause()
    suspend fun cancel()
}
