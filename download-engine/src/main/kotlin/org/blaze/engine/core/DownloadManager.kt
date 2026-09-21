package org.blaze.engine.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadMetadata
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.execution.DownloadExecutorImpl
import org.blaze.engine.persistence.DownloadRecord
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.retry.DefaultRetryPolicy
import org.blaze.engine.retry.RetryPolicy
import org.blaze.engine.scheduler.DownloadScheduler
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.storage.FileStorage
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class DownloadManager(
    private val scope: CoroutineScope,
    private val repository: DownloadRepository,
    private val settingsRepository: EngineSettingsRepository,
    private val storage: FileStorage = DefaultFileStorage(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val executorFactory: ((DownloadTask) -> DownloadExecutor)? = null
) : DownloadEngine {

    private val logger = LoggerFactory.getLogger(DownloadManager::class.java)

    private val persistSignal = Channel<Unit>(Channel.CONFLATED)
    private val tasks = MutableStateFlow<Map<DownloadId, DownloadTask>>(emptyMap())

    private val scheduler = DownloadScheduler(
        scope = scope,
        settingsRepository = settingsRepository,
        executorFactory = { task ->
            executorFactory?.invoke(task) ?: DownloadExecutorImpl(
                initialTask = task,
                httpClient = httpClient,
                storage = storage,
                settingsRepository = settingsRepository,
                retryPolicy = DefaultRetryPolicy(settingsRepository.settings.value),
                onMetadataResolved = { id, bytes -> cacheMetadata(id, bytes) }
            )
        },
        onTaskUpdated = { task ->
            tasks.update { it + (task.id to task) }
            persist()
            if (task.state == DownloadState.Completed) {
                cleanupMetadata(task.id)
            }
        }
    )

    init {
        scope.launch(Dispatchers.IO) {
            try {
                storage.ensureDirectory(repository.storageDir.resolve("cache"))
            } catch (e: Exception) {
                logger.error("Failed to create cache directory", e)
            }
            persistSignal.consumeEach {
                try {
                    writeSnapshot()
                } catch (_: Exception) {}
            }
        }
        loadTasks()
    }

    companion object {
        fun createDefaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                // No absolute cap on the whole call: a streaming download of a large or
                // slow file can legitimately take longer than any fixed timeout, and a
                // finite request timeout would kill it mid-body with "Request timeout has
                // expired" on every attempt. Dead connections are still detected by the
                // socket (read-inactivity) timeout below, which surfaces as a retryable
                // network error and triggers auto-retry.
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
            followRedirects = false
        }
    }

    private fun loadTasks() {
        scope.launch(Dispatchers.IO) {
            val records = try {
                repository.loadAll()
            } catch (e: Exception) {
                logger.error("Failed to load tasks", e)
                emptyList()
            }

            val settings = settingsRepository.settings.value
            val loadedTasks = records.mapNotNull { record ->
                try {
                    val task = parseRecord(record)
                    val finalState = when {
                        task.state.isActive || task.state == DownloadState.Paused || task.state == DownloadState.Failed -> {
                            if (settings.resumeDownloadsOnStartup) DownloadState.Queued else DownloadState.Paused
                        }
                        task.state == DownloadState.Queued -> {
                            if (settings.startQueuedOnStartup) DownloadState.Queued else DownloadState.Paused
                        }
                        else -> task.state
                    }
                    task.copy(state = finalState)
                } catch (e: Exception) {
                    logger.warn("Skipping corrupt record ${record.id}", e)
                    null
                }
            }.associateBy { it.id }

            tasks.update { current -> loadedTasks + current }
            scheduler.updateTasks(tasks.value)
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
                DownloadRequest.Torrent(record.name, source, Path.of(record.destination), record.fileIndices)
            }
            else -> error("Unknown download type '${record.type}' for record ${record.id}")
        }

        return DownloadTask(
            id = DownloadId(record.id),
            name = record.name,
            request = request,
            state = DownloadState.fromString(record.state),
            totalBytes = record.totalBytes,
            downloadedBytes = record.downloadedBytes,
            downloadSpeed = 0,
            retryCount = record.retryCount,
            createdAt = Instant.ofEpochMilli(record.addedAt),
            scheduledAt = record.scheduledAt?.let { Instant.ofEpochMilli(it) },
            files = record.files
        )
    }

    private fun serializeState(state: DownloadState): String =
        DownloadState.toString(state)

    private fun persist() {
        persistSignal.trySend(Unit)
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
                addedAt = task.createdAt.toEpochMilli(),
                scheduledAt = task.scheduledAt?.toEpochMilli(),
                retryCount = task.retryCount,
                fileIndices = (task.request as? DownloadRequest.Torrent)?.fileIndices,
                files = task.files
            )
        }
        withContext(Dispatchers.IO) {
            repository.saveAll(records)
        }
    }

    private fun cacheMetadata(id: DownloadId, bytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            val task = tasks.value[id] ?: return@launch
            val request = task.request as? DownloadRequest.Torrent ?: return@launch
            
            if (request.torrentSource is TorrentSource.File) {
                val path = request.torrentSource.path
                if (path.startsWith(repository.storageDir.resolve("cache"))) return@launch
            }

            try {
                val cacheDir = repository.storageDir.resolve("cache")
                storage.ensureDirectory(cacheDir)
                val torrentFile = cacheDir.resolve("${id.value}.torrent")
                Files.write(torrentFile, bytes)

                val newRequest = request.copy(torrentSource = TorrentSource.File(torrentFile))
                val updatedTask = task.copy(request = newRequest)
                tasks.update { it + (id to updatedTask) }
                persist()
            } catch (e: Exception) {
                logger.error("Failed to cache metadata", e)
            }
        }
    }

    private fun getCacheKey(request: DownloadRequest): String {
        val sourceString = when (request) {
            is DownloadRequest.Http -> request.url
            is DownloadRequest.Torrent -> when (val s = request.torrentSource) {
                is TorrentSource.Magnet -> s.uri
                is TorrentSource.File -> s.path.toAbsolutePath().toString()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(sourceString.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun cleanupMetadata(id: DownloadId) {
        scope.launch(Dispatchers.IO) {
            try {
                val torrentFile = repository.storageDir.resolve("cache").resolve("${id.value}.torrent")
                storage.delete(torrentFile)
            } catch (e: Exception) {
                logger.error("Failed to cleanup metadata", e)
            }
        }
    }

    override suspend fun fetchMetadata(request: DownloadRequest): DownloadMetadata? = withContext(Dispatchers.IO) {
        val tempId = DownloadId.generate()
        val tempTask = DownloadTask(tempId, request.name, request, DownloadState.Starting, null, 0, 0)
        
        val executor = DownloadExecutorImpl(
            initialTask = tempTask,
            httpClient = httpClient,
            storage = storage,
            settingsRepository = settingsRepository,
            retryPolicy = object : RetryPolicy {
                override fun getNextDelay(error: DownloadError, retryCount: Int): Long? = null
            },
            onMetadataResolved = { _, bytes ->
                try {
                    val cacheDir = repository.storageDir.resolve("cache")
                    Files.createDirectories(cacheDir)
                    val key = getCacheKey(request)
                    val torrentFile = cacheDir.resolve("$key.torrent")
                    Files.write(torrentFile, bytes)
                } catch (_: Exception) {}
            }
        )

        val metadata = withTimeoutOrNull(30.seconds) {
            executor.execute().firstOrNull { it.totalBytes != null && it.totalBytes > 0 }
        }

        metadata?.let { DownloadMetadata(it.name, it.totalBytes, it.files) }
    }

    override suspend fun enqueue(
        request: DownloadRequest,
        totalBytes: Long?,
        files: List<DownloadFileMetadata>?,
        scheduledAt: Instant?
    ): DownloadId {
        val id = DownloadId.generate()
        val finalRequest = if (request is DownloadRequest.Torrent && request.torrentSource is TorrentSource.Magnet) {
            val key = getCacheKey(request)
            val cachedTorrentFile = repository.storageDir.resolve("cache").resolve("$key.torrent")
            if (Files.isRegularFile(cachedTorrentFile)) {
                request.copy(torrentSource = TorrentSource.File(cachedTorrentFile))
            } else {
                request
            }
        } else {
            request
        }

        val task = DownloadTask(
            id = id,
            name = finalRequest.name,
            request = finalRequest,
            state = DownloadState.Queued,
            totalBytes = totalBytes,
            downloadedBytes = 0,
            downloadSpeed = 0,
            scheduledAt = scheduledAt,
            files = files
        )
        tasks.update { it + (id to task) }
        scheduler.enqueue(task)
        persist()
        return id
    }

    override suspend fun start(id: DownloadId) {
        scheduler.start(id)
    }

    override suspend fun pause(id: DownloadId) {
        scheduler.pause(id)
        persist()
    }

    override suspend fun resume(id: DownloadId) {
        scheduler.start(id)
    }

    override suspend fun cancel(id: DownloadId) {
        scheduler.cancel(id)
        persist()
    }

    override suspend fun retry(id: DownloadId) {
        scheduler.start(id)
    }

    override suspend fun remove(id: DownloadId, deleteFiles: Boolean) {
        val task = tasks.value[id]
        scheduler.remove(id)
        tasks.update { it - id }
        cleanupMetadata(id)

        if (deleteFiles && task != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val dest = task.request.destination
                    if (task.request is DownloadRequest.Torrent) {
                        storage.deleteRecursively(dest.resolve(task.name))
                    } else {
                        storage.delete(dest)
                        storage.delete(storage.getPartialFile(dest).toPath())
                    }
                } catch (e: Exception) {
                    logger.error("Failed to delete files", e)
                }
            }
        }
        persist()
    }

    override suspend fun getTask(id: DownloadId): DownloadTask? = tasks.value[id]

    override fun observeTask(id: DownloadId): Flow<DownloadTask> = scheduler.observeTask(id)

    override fun observeAllTasks(): StateFlow<List<DownloadTask>> = scheduler.observeAllTasks()

    override suspend fun shutdown() {
        scheduler.shutdown()
        writeSnapshot()
        persistSignal.close()
        httpClient.close()
        scope.cancel()
    }
}