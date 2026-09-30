package org.blaze.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.blaze.download.api.ApiCapabilities
import org.blaze.download.api.ApiErrorBody
import org.blaze.download.api.ApiErrorCode
import org.blaze.download.api.AuthMode
import org.blaze.download.api.BandwidthControlSupport
import org.blaze.download.api.DownloadDto
import org.blaze.download.api.DownloadEvent
import org.blaze.download.api.DownloadMetadataDto
import org.blaze.download.api.DownloadPriority
import org.blaze.download.api.DownloadState
import org.blaze.download.api.DownloadsApi
import org.blaze.download.api.NewDownloadRequest
import org.blaze.download.api.PortableInfoDto
import org.blaze.download.api.PrioritySupport
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.service.mapping.RequestMapper
import org.blaze.service.mapping.toApiError
import org.blaze.service.mapping.toDto
import org.blaze.service.mapping.toPublic
import org.blaze.service.security.IdempotencyStore
import org.blaze.service.security.PathValidator
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * The application-level download service: the only implementation of [DownloadsApi] and the single place
 * the rest of the system is allowed to talk to the engine.
 *
 * Responsibilities kept here so no client duplicates them: request/source validation, destination
 * confinement, engine↔public-DTO mapping, idempotent create, and translating the engine's high-frequency
 * task stream into a coalesced, rate-limited event stream. It holds no byte-transfer work - the API sits
 * entirely outside the hot path; it only creates and steers downloads, and the engine streams bytes.
 *
 * Creating a download is also where a destination is *claimed* (step 3 of [create]): the one moment at
 * which "that folder is already taken" reliably means somebody else's copy, and so the only safe place
 * to decide that a new one has to be named differently.
 *
 * Lifecycle races (start+pause, resume+cancel, remove+completion) are left to the engine scheduler, which
 * already serializes every state-mutating operation behind its queue mutex; this layer is a thin, stateless
 * forwarder plus the event projector.
 */
class ApplicationDownloadService(
    private val engine: DownloadEngine,
    scope: CoroutineScope,
    private val pathValidator: PathValidator = PathValidator.TRUSTED,
    private val settingsRepository: EngineSettingsRepository,
    authMode: AuthMode = AuthMode.NONE,
    private val eventMinIntervalMillis: Long = 400,
) : DownloadsApi {

    private val logger = LoggerFactory.getLogger(ApplicationDownloadService::class.java)

    override val capabilities: ApiCapabilities = ApiCapabilities(
        apiVersion = org.blaze.download.api.ApiVersion.CURRENT,
        supportsHttp = true,
        supportsHttps = true,
        supportsTorrent = true,
        supportsMagnet = true,
        supportsPortableDownloads = true,
        supportsPauseResume = true,
        supportsCancel = true,
        supportsRetry = true,
        supportsScheduling = true,
        supportsEventStream = true,
        bandwidthControl = BandwidthControlSupport.GLOBAL_ONLY,
        prioritySupport = PrioritySupport.EXPLICIT,
        authentication = authMode,
        requiresLocalhost = true,
    )

    private val events = MutableSharedFlow<DownloadEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    private val idempotency = IdempotencyStore()

    // Event projection state, per download id.
    private val lastState = ConcurrentHashMap<String, DownloadState>()
    private val lastProgressAt = ConcurrentHashMap<String, Long>()
    // The last error code seen per download, with "" meaning "no error". A ConcurrentHashMap forbids
    // null values, so the cleared-error case is carried as the empty sentinel rather than a null.
    private val lastErrorCode = ConcurrentHashMap<String, String>()

    init {
        scope.launch { projectEvents() }
    }

    override val apiVersion: Int get() = org.blaze.download.api.ApiVersion.CURRENT

    override fun snapshot(): List<DownloadDto> = engine.observeAllTasks().value.map { it.toDto() }

    override fun observe(): Flow<List<DownloadDto>> =
        engine.observeAllTasks().map { tasks -> tasks.map { it.toDto() } }

    override fun events(): Flow<DownloadEvent> = events.asSharedFlow()

    override suspend fun get(id: String): DownloadDto? = engine.getTask(DownloadId(id))?.toDto()

    override suspend fun create(request: NewDownloadRequest): DownloadDto {
        // 1. Idempotent replay: a retried POST for a known key returns the original download.
        val key = request.idempotencyKey
        if (key != null) {
            val existingId = idempotency.lookup(key)
            if (existingId != null) {
                engine.getTask(DownloadId(existingId))?.let { return it.toDto() }
            }
        }

        val destinationDir = pathValidator.validateDirectory(request.destination)
        val engineRequest = RequestMapper.toEngineRequest(request, destinationDir)

        // 2. Unambiguous duplicate: the same source that is still on its way here.
        findActiveDuplicate(engineRequest)?.let { return it.toDto() }

        // 3. Claim the destination. A torrent keeps its own folder, so a second copy of one that has
        //    already been fetched cannot share it; this is settled now, while a taken folder still
        //    proves a collision, and not at transfer time when the folder is the download's own.
        val behavior = settingsRepository.settings.value.fileConflictBehavior
        val claimed = RequestMapper.claimDestination(engineRequest, behavior, Files::exists)
        if (claimed.destination != engineRequest.destination) {
            logger.info(
                "Destination {} is taken; starting the download in {}",
                engineRequest.destination,
                claimed.destination,
            )
        }

        val engineFiles = request.files?.map { org.blaze.engine.api.DownloadFileMetadata(path = it.path, size = it.size) }
        val id = engine.enqueue(
            request = claimed,
            totalBytes = request.expectedSizeBytes,
            files = engineFiles,
            scheduledAt = request.scheduledAtEpochMs?.let { java.time.Instant.ofEpochMilli(it) },
            priority = request.priority?.let { org.blaze.engine.api.DownloadPriority.valueOf(it.name) },
        )
        if (key != null) idempotency.record(key, id.value)
        if (request.autoStart) {
            runCatching { engine.start(id) }
                .onFailure { logger.warn("auto-start failed for {}", id.value, it) }
        }
        return engine.getTask(id)?.toDto()
            ?: DownloadDto(
                id = id.value,
                name = claimed.name,
                state = DownloadState.QUEUED,
                source = sourceInfoOf(request.source),
                destination = claimed.destination.toString(),
                totalBytes = request.expectedSizeBytes,
                createdAtEpochMs = System.currentTimeMillis(),
                resumable = true,
            )
    }

    private fun sourceInfoOf(source: org.blaze.download.api.DownloadSource): org.blaze.download.api.SourceInfo =
        when (source) {
            is org.blaze.download.api.DownloadSource.Url -> org.blaze.download.api.SourceInfo(
                kind = if (source.url.startsWith("https", true)) org.blaze.download.api.SourceKind.HTTPS
                else org.blaze.download.api.SourceKind.HTTP,
                locator = source.url,
            )
            is org.blaze.download.api.DownloadSource.Magnet ->
                org.blaze.download.api.SourceInfo(org.blaze.download.api.SourceKind.MAGNET, source.uri)
            is org.blaze.download.api.DownloadSource.TorrentFile ->
                if (source.path.startsWith("http://", true) || source.path.startsWith("https://", true)) {
                    org.blaze.download.api.SourceInfo(org.blaze.download.api.SourceKind.TORRENT_URL, source.path)
                } else {
                    org.blaze.download.api.SourceInfo(org.blaze.download.api.SourceKind.TORRENT_FILE, source.path)
                }
        }

    private fun findActiveDuplicate(engineRequest: org.blaze.engine.api.DownloadRequest): DownloadTask? {
        val signature = sourceSignature(engineRequest) ?: return null
        return engine.observeAllTasks().value.firstOrNull { task ->
            task.isStillTransferring() && sourceSignature(task.request) == signature
        }
    }

    /** A comparable, secret-free identity for a source: scheme + locator + resolved destination. */
    private fun sourceSignature(request: org.blaze.engine.api.DownloadRequest): String? = when (request) {
        is org.blaze.engine.api.DownloadRequest.Http -> "http|${request.url}|${request.destination}"
        is org.blaze.engine.api.DownloadRequest.Torrent -> {
            val src = when (val s = request.torrentSource) {
                is org.blaze.engine.api.TorrentSource.Magnet -> s.uri
                is org.blaze.engine.api.TorrentSource.File -> s.path.toString()
                is org.blaze.engine.api.TorrentSource.Url -> s.url
            }
            "torrent|$src|${request.destination}"
        }
    }

    override suspend fun start(id: String) = guard(id) { engine.start(DownloadId(id)) }
    override suspend fun pause(id: String) = guard(id) { engine.pause(DownloadId(id)) }
    override suspend fun resume(id: String) = guard(id) { engine.resume(DownloadId(id)) }
    override suspend fun cancel(id: String) = guard(id) { engine.cancel(DownloadId(id)) }
    override suspend fun retry(id: String) = guard(id) { engine.retry(DownloadId(id)) }

    override suspend fun remove(id: String, deleteFiles: Boolean) = guard(id) { engine.remove(DownloadId(id), deleteFiles) }

    override suspend fun setPriority(id: String, priority: DownloadPriority) = guard(id) {
        engine.setPriority(DownloadId(id), org.blaze.engine.api.DownloadPriority.valueOf(priority.name))
    }

    /**
     * Shared plumbing for the lifecycle verbs: reject an unknown id with [ApiErrorCode.DOWNLOAD_NOT_FOUND]
     * (the engine itself no-ops on a missing task), then forward and translate any failure into a stable
     * [org.blaze.download.api.DownloadApiException].
     */
    private suspend fun guard(id: String, block: suspend () -> Unit) {
        if (engine.getTask(DownloadId(id)) == null) {
            throw org.blaze.download.api.DownloadApiException(ApiErrorCode.DOWNLOAD_NOT_FOUND, "no such download: $id")
        }
        runCatching { block() }.getOrElse { throw org.blaze.download.api.DownloadApiException(it.toApiError(), it) }
    }

    override suspend fun shutdown() {
        engine.shutdown()
    }

    override suspend fun probe(request: NewDownloadRequest): DownloadMetadataDto? {
        val tmpDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
        val engineRequest = RequestMapper.toEngineRequest(request, tmpDir)
        return engine.fetchMetadata(engineRequest)?.toDto()
    }

    override suspend fun detectPortable(path: String): PortableInfoDto? =
        engine.detectPortableDownload(java.nio.file.Path.of(path))?.toDto()

    override suspend fun importPortable(artifactPath: String, destinationDir: String): String? {
        val destination = pathValidator.validateDirectory(destinationDir)
        return engine.importPortableDownload(java.nio.file.Path.of(artifactPath), destination)?.value
    }

    private suspend fun projectEvents() {
        engine.observeAllTasks().collect { tasks -> onTasksChanged(tasks) }
    }

    private fun onTasksChanged(tasks: List<DownloadTask>) {
        val now = System.currentTimeMillis()
        val liveIds = tasks.map { it.id.value }.toSet()

        for (id in lastState.keys) {
            if (id !in liveIds) {
                lastState.remove(id)
                lastProgressAt.remove(id)
                lastErrorCode.remove(id)
                emit(DownloadEvent.Removed(id))
            }
        }

        for (task in tasks) {
            val id = task.id.value
            val state = task.state.toPublic()
            val prev = lastState[id]

            if (prev == null) {
                emit(DownloadEvent.Created(id, task.toDto()))
            } else if (prev != state) {
                emitTransition(id, state, task)
            }

            // Error may change without the state moving (a transient failure being retried).
            val errorKey = task.error?.toApiError()?.code?.name ?: ""
            if (errorKey != lastErrorCode[id]) {
                lastErrorCode[id] = errorKey
                if (state != DownloadState.FAILED) {
                    task.error?.let { emit(DownloadEvent.ErrorChanged(id, it.toApiError())) }
                }
            }

            // Coalesced progress: at most one per interval per download, so an observer cannot flood us.
            val lastAt = lastProgressAt[id] ?: 0L
            if (now - lastAt >= eventMinIntervalMillis) {
                lastProgressAt[id] = now
                emit(
                    DownloadEvent.Progress(
                        downloadId = id,
                        downloadedBytes = task.downloadedBytes,
                        totalBytes = task.totalBytes,
                        speedBytesPerSecond = task.downloadSpeed.coerceAtLeast(0),
                        etaSeconds = task.eta?.inWholeSeconds,
                        progress = task.progress,
                    )
                )
            }
            lastState[id] = state
        }
    }

    private fun emitTransition(id: String, state: DownloadState, task: DownloadTask) {
        when (state) {
            DownloadState.STARTING, DownloadState.RESUMING ->
                if (state == DownloadState.STARTING) emit(DownloadEvent.Started(id)) else emit(DownloadEvent.Resumed(id))
            DownloadState.PAUSED -> emit(DownloadEvent.Paused(id))
            DownloadState.CANCELLED -> emit(DownloadEvent.Cancelled(id))
            DownloadState.COMPLETED -> emit(DownloadEvent.Completed(id, task.toDto()))
            DownloadState.FAILED -> emit(DownloadEvent.Failed(id, task.error?.toApiError() ?: ApiErrorBody(ApiErrorCode.DOWNLOAD_FAILED, "download failed")))
            else -> Unit
        }
    }

    private fun emit(event: DownloadEvent) {
        // tryEmit never suspends; the buffer + DROP_OLDEST policy keep a slow consumer from stalling the
        // engine. Losing an intermediate progress tick is acceptable; losing it is better than blocking.
        events.tryEmit(event)
    }
}

/**
 * Whether this task still has bytes on the way to it - the only kind of download that makes adding the
 * same source twice a duplicate rather than a second copy.
 *
 * A seeding torrent is deliberately excluded. It is live and it is uploading, but its download finished
 * long ago: someone adding that source again is asking for another copy of files they already have, and
 * the honest answer is a second download, not a silent no-op that leaves the list exactly as it was.
 */
private fun DownloadTask.isStillTransferring(): Boolean = when (state) {
    org.blaze.engine.api.DownloadState.Completed,
    org.blaze.engine.api.DownloadState.Failed,
    org.blaze.engine.api.DownloadState.Cancelled,
    org.blaze.engine.api.DownloadState.Seeding -> false
    else -> true
}
