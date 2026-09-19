package org.blaze.engine.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
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
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Fixes applied relative to the original:
 *  - start() now tears down (cancels + joins) any previous job/downloader for the same id
 *    before creating a new one, so pause -> resume -> pause no longer leaks bt clients.
 *  - Job cancellation is joined (cancelAndJoin), not fire-and-forget, so a stale collector
 *    can't clobber state written by the job that replaced it.
 *  - Persistence is funneled through a single-consumer conflated channel, so concurrent
 *    persist() calls can't interleave and write an older snapshot over a newer one.
 *  - loadTasks() parses each record independently; a single corrupt row is skipped
 *    instead of throwing inside scope.launch and taking down every other download.
 *  - The freshly-loaded map is merged into (not replacing) the current map, so anything
 *    enqueued while the load was in flight survives.
 *  - remove(deleteFiles = true) recursively deletes directories (multi-file torrents).
 *  - State (de)serialization is symmetric and doesn't depend on toString().
 *  - Removed `!!` lookups that could NPE on a task removed concurrently.
 *  - Metadata resolved after a magnet is persisted as soon as it arrives, not just on
 *    Completed/Failed, so a kill mid-download doesn't lose the resolved name/size.
 */
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

    // Single-consumer channel so persistence writes are strictly ordered and always
    // write the latest snapshot; CONFLATED collapses bursts of persist() calls into one.
    private val persistSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (unused in persistSignal) {
                writeSnapshot()
            }
        }
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

            val loadedTasks = records.mapNotNull { record ->
                try {
                    parseRecord(record)
                } catch (e: Exception) {
                    println("Skipping corrupt record ${record.id}: ${e.message}")
                    null
                }
            }

            // Merge, don't replace: anything enqueued while this load was in flight
            // (current) takes priority over what we just loaded from disk.
            tasks.update { current -> loadedTasks.associateBy { it.id } + current }
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
                val source = if (record.magnetUri != null) {
                    TorrentSource.Magnet(record.magnetUri)
                } else {
                    TorrentSource.File(
                        Path.of(record.torrentPath ?: error("Torrent record ${record.id} missing torrentPath"))
                    )
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
        // No downloader is running right after a reload, so anything that was
        // actively in-flight when we last persisted resumes as Queued rather
        // than being stuck showing "Downloading" with no job behind it.
        else -> DownloadState.Queued
    }

    // Uses the state's own class name instead of toString(), so this stays correct
    // even if a DownloadState variant later gains constructor parameters.
    private fun serializeState(state: DownloadState): String =
        state::class.simpleName?.uppercase() ?: "QUEUED"

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
                addedAt = task.createdAt.toEpochMilli()
            )
        }
        try {
            withContext(Dispatchers.IO) {
                repository.saveAll(records)
            }
        } catch (e: Exception) {
            println("Failed to persist tasks: ${e.message}")
        }
    }

    override suspend fun fetchMetadata(request: DownloadRequest): DownloadMetadata? = withContext(Dispatchers.IO) {
        val downloader = when (request) {
            is DownloadRequest.Http -> httpDownloaderFactory(request)
            is DownloadRequest.Torrent -> torrentDownloaderFactory(request)
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

        // Tear down any previous job/downloader for this id and WAIT for it to actually
        // stop before wiring up a new one. Previously the old downloader instance was
        // just overwritten in the map without being cancelled, which leaked a second
        // live bt client (double DHT/tracker announces) on every pause -> resume.
        jobs[id]?.cancelAndJoin()
        downloaders.remove(id)?.let { old ->
            try {
                old.cancel()
            } catch (_: Exception) {
                // best-effort teardown of the superseded downloader
            }
        }

        val downloader = when (val request = task.request) {
            is DownloadRequest.Http -> httpDownloaderFactory(request)
            is DownloadRequest.Torrent -> torrentDownloaderFactory(request)
        }

        downloaders[id] = downloader

        var metadataPersisted = task.totalBytes != null

        val job = scope.launch {
            try {
                downloader.download().collect { updatedTask ->
                    tasks.update { current ->
                        // Don't resurrect a task that's since been removed.
                        if (current.containsKey(id)) {
                            current + (id to updatedTask.copy(id = id, request = task.request))
                        } else {
                            current
                        }
                    }
                    when {
                        updatedTask.state == DownloadState.Completed || updatedTask.state == DownloadState.Failed -> {
                            persist()
                        }
                        // Magnet metadata (name/size) resolves mid-flight; persist it as soon
                        // as it's known so a crash before completion doesn't lose it.
                        !metadataPersisted && updatedTask.totalBytes != null -> {
                            metadataPersisted = true
                            persist()
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    println("Error in download collection for ${task.name}: ${e.message}")
                    tasks.update { current ->
                        current[id]?.let { current + (id to it.copy(state = DownloadState.Failed)) } ?: current
                    }
                    persist()
                }
            }
        }
        jobs[id] = job
    }

    override suspend fun pause(id: DownloadId) {
        val downloader = downloaders[id] ?: return
        downloader.pause()
        jobs[id]?.cancelAndJoin()
        tasks.update { current ->
            current[id]?.let { current + (id to it.copy(state = DownloadState.Paused)) } ?: current
        }
        persist()
    }

    override suspend fun resume(id: DownloadId) {
        start(id)
    }

    override suspend fun cancel(id: DownloadId) {
        jobs[id]?.cancelAndJoin()
        downloaders[id]?.cancel()
        tasks.update { current ->
            current[id]?.let { current + (id to it.copy(state = DownloadState.Cancelled)) } ?: current
        }
        persist()
    }

    override suspend fun retry(id: DownloadId) {
        start(id)
    }

    override suspend fun remove(id: DownloadId, deleteFiles: Boolean) {
        val task = tasks.value[id]
        cancel(id)

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
                        // Multi-file torrents land in a directory; File.delete() silently
                        // no-ops on a non-empty one, so nothing was ever actually removed.
                        destination.deleteRecursively()
                    } else {
                        destination.delete()
                        destination.resolveSibling("${destination.name}.part").delete()
                    }
                }
            } catch (e: Exception) {
                println("Failed to delete files for ${task.name}: ${e.message}")
            }
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
        // Stop collectors first so nothing can write into tasks after our final snapshot.
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