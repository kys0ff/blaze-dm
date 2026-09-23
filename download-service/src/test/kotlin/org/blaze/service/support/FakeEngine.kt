package org.blaze.service.support

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadMetadata
import org.blaze.engine.api.DownloadPriority
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.PortableDownloadInfo
import java.time.Instant
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A controllable in-memory [DownloadEngine] for unit tests of [org.blaze.service.ApplicationDownloadService].
 *
 * It is deliberately not a "real" downloader: it tracks the same shapes the service maps (tasks, states,
 * lifecycle calls) so the unit tests assert the service's *contract* - validation, idempotency, mapping,
 * race-safe forwarding - without touching the network or disk. Real end-to-end transfers are covered by
 * the integration tests against the actual engine.
 */
class FakeEngine(
    initial: List<DownloadTask> = emptyList(),
    private val metadataResult: DownloadMetadata? = null,
    private val portableResult: PortableDownloadInfo? = null,
    private val importResult: DownloadId? = null,
) : DownloadEngine {

    private val counter = AtomicInteger(0)
    private val _tasks = MutableStateFlow(initial)
    override fun observeAllTasks(): StateFlow<List<DownloadTask>> = _tasks
    override fun observeTask(id: DownloadId): Flow<DownloadTask> =
        _tasks.map { list -> list.first { it.id == id } }

    /** Every lifecycle verb call, in order, as "verb:id" - lets tests assert forwarding + id checks. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var shutdownCalled = false
        private set

    val tasks: List<DownloadTask> get() = _tasks.value

    fun setState(id: String, state: DownloadState) = mutate(id) { it.copy(state = state) }
    fun setProgress(id: String, downloaded: Long, total: Long?) =
        mutate(id) { it.copy(downloadedBytes = downloaded, totalBytes = total) }

    private fun mutate(id: String, block: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id.value == id) block(it) else it }
    }

    override suspend fun fetchMetadata(request: DownloadRequest): DownloadMetadata? = metadataResult

    override suspend fun detectPortableDownload(path: Path): PortableDownloadInfo? = portableResult

    override suspend fun importPortableDownload(artifact: Path, destination: Path): DownloadId? = importResult

    override suspend fun enqueue(
        request: DownloadRequest,
        totalBytes: Long?,
        files: List<DownloadFileMetadata>?,
        scheduledAt: Instant?,
        priority: DownloadPriority?,
    ): DownloadId {
        val id = DownloadId("dl-${counter.incrementAndGet()}")
        _tasks.value = _tasks.value + DownloadTask(
            id = id,
            name = request.name,
            request = request,
            state = DownloadState.Queued,
            totalBytes = totalBytes,
            downloadedBytes = 0,
            downloadSpeed = 0,
            scheduledAt = scheduledAt,
            priority = priority ?: DownloadPriority.NORMAL,
            files = files,
        )
        return id
    }

    override suspend fun setPriority(id: DownloadId, priority: DownloadPriority) {
        calls += "setPriority:${id.value}"
        mutate(id.value) { it.copy(priority = priority) }
    }

    override suspend fun start(id: DownloadId) = record("start", id) { DownloadState.Downloading }
    override suspend fun pause(id: DownloadId) = record("pause", id) { DownloadState.Paused }
    override suspend fun resume(id: DownloadId) = record("resume", id) { DownloadState.Downloading }
    override suspend fun cancel(id: DownloadId) = record("cancel", id) { DownloadState.Cancelled }
    override suspend fun retry(id: DownloadId) = record("retry", id) { DownloadState.Queued }

    override suspend fun remove(id: DownloadId, deleteFiles: Boolean) {
        calls += "remove:${id.value}"
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }

    override suspend fun getTask(id: DownloadId): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    override suspend fun shutdown() {
        shutdownCalled = true
    }

    private suspend fun record(verb: String, id: DownloadId, target: () -> DownloadState) {
        calls += "$verb:${id.value}"
        if (getTask(id) == null) return // engine no-ops on unknown id; service's guard rejects earlier
        mutate(id.value) { it.copy(state = target()) }
    }
}
