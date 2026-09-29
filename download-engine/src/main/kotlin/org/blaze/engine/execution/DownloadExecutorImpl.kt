package org.blaze.engine.execution

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.blaze.engine.api.CopyName
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.metrics.EngineMetrics
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.network.TorrentNetworkClient
import org.blaze.engine.network.TorrentNetworkEvent
import org.blaze.engine.retry.RetryPolicy
import org.blaze.engine.settings.DEFAULT_USER_AGENT
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.engine.storage.FileStorage
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Drives one download attempt of a single task and translates protocol events into
 * [DownloadTask] state.
 *
 * HTTP is delegated to [HttpDownloadCoordinator] (probe, connection pool, disk writes, resume
 * bookkeeping); torrents are driven through the `bt` client. Both end in exactly one terminal
 * state, which is what the retry layer in [execute] keys off.
 */
class DownloadExecutorImpl(
    private val initialTask: DownloadTask,
    private val httpClient: HttpClient,
    private val storage: FileStorage,
    private val settingsRepository: EngineSettingsRepository,
    private val retryPolicy: RetryPolicy,
    private val onMetadataResolved: (DownloadId, ByteArray) -> Unit,
    private val limiter: BandwidthLimiter = BandwidthLimiter(),
    private val metrics: EngineMetrics = EngineMetrics()
) : DownloadExecutor {
    private val logger = LoggerFactory.getLogger(DownloadExecutorImpl::class.java)

    override fun execute(): Flow<DownloadTask> = channelFlow {
        var currentTask = initialTask
        logger.debug("Starting execution of task {} ({})", currentTask.id, currentTask.request::class.simpleName)
        send(currentTask)

        try {
            val flow = when (val request = currentTask.request) {
                is DownloadRequest.Http -> executeHttp(request, currentTask)
                is DownloadRequest.Torrent -> executeTorrent(request, currentTask)
            }

            flow.collect { updatedTask ->
                currentTask = updatedTask
                send(updatedTask)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Unexpected error in DownloadExecutor for {}", currentTask.id, e)
            currentTask = currentTask.copy(
                state = DownloadState.Failed,
                error = DownloadError.Unknown(e.message ?: "Unknown error")
            )
            send(currentTask)
        }

        if (currentTask.state == DownloadState.Failed) {
            val delayMs = retryPolicy.getNextDelay(currentTask.error ?: DownloadError.Unknown("Unknown error"), currentTask.retryCount)
            if (delayMs != null) {
                logger.info(
                    "Scheduling retry for {} in {}ms (retry {})",
                    currentTask.id, delayMs, currentTask.retryCount + 1
                )
                val retryingTask = currentTask.copy(
                    state = DownloadState.Queued,
                    scheduledAt = Instant.now().plusMillis(delayMs),
                    retryCount = currentTask.retryCount + 1
                )
                currentTask = retryingTask // TODO: Warning: Assigned value is never read
                send(retryingTask)
            }
        }
    }

    private fun executeHttp(request: DownloadRequest.Http, task: DownloadTask): Flow<DownloadTask> =
        channelFlow {
            // The limiter arrives already configured by the engine: this download only spends
            // tokens from it. Configuring it per download would make the ceiling depend on how
            // many transfers happen to be running.
            val settings = settingsRepository.settings.value

            val (finalDestination, finalPartialPath) = resolveDestination(
                request.destination,
                settings.fileConflictBehavior
            )
            if (finalDestination == null) {
                // Skipped
                send(
                    task.copy(
                        state = DownloadState.Completed,
                        downloadedBytes = storage.size(request.destination),
                        totalBytes = storage.size(request.destination),
                        progress = 1f
                    )
                )
                return@channelFlow
            }

            // Persisting the resolved name keeps the partial file and its resume sidecar
            // pointing at the same target across retries and restarts.
            val resolved = if (finalDestination == request.destination) request
            else request.copy(destination = finalDestination)
            // and the label follows it: a transfer that ended up as "movie (1).mkv" has to read that
            // way in the list too, or the disambiguation the user asked for looks like it never happened.
            val labelled = if (resolved.destination == request.destination) task
            else task.copy(name = resolved.destination.fileName.toString())

            val client = HttpNetworkClient(
                client = httpClient,
                userAgent = settings.httpUserAgent.ifBlank { DEFAULT_USER_AGENT },
                maxRedirects = settings.maxRedirects
            )
            val coordinator = HttpDownloadCoordinator(
                client = client,
                storage = storage,
                limiter = limiter,
                settings = settings,
                metrics = metrics
            )

            // An explicit per-download request overrides everything: priority never second-guesses a
            // hand-picked connection count. Otherwise the global budget is scaled by this download's
            // priority, so an idle transfer trickles along on one connection while an urgent one takes
            // a larger share. Either way the worker count stays bounded - more connections than this
            // only gets us throttled or banned by the host.
            val connections = (
                if (request.segmentCount > 0) request.segmentCount
                else task.priority.connectionsFor(settings.maxConnectionsPerDownload)
            ).coerceIn(1, EngineSettingsRepository.MAX_CONNECTIONS_PER_DOWNLOAD)
            if (finalPartialPath.let { storage.exists(it) } && storage.size(finalPartialPath) > 0) {
                metrics.recordResumed()
            }
            logger.info("HTTP transfer for {} using up to {} connection(s)", resolved.destination.fileName, connections)

            var totalBytes = task.totalBytes
            var downloadedBytes = task.downloadedBytes
            // Learned from the first response that proves it (a 206, an `Accept-Ranges` answer, an
            // ignored `Range`); carried on every update so a client can warn before it pauses.
            var resumeSupported: Boolean? = task.resumeSupported
            val progress = HttpProgress()

            val outcome = coordinator.download(
                request = resolved,
                destination = finalDestination,
                partial = finalPartialPath,
                connections = connections,
                onRangeSupport = { supported -> resumeSupported = supported }
            ) { update ->
                totalBytes = update.totalBytes ?: totalBytes
                downloadedBytes = update.downloadedBytes
                val speed = progress.sample(update.downloadedBytes)
                send(
                    updateTask(
                        task = labelled,
                        request = resolved,
                        state = DownloadState.Downloading,
                        totalBytes = totalBytes,
                        downloadedBytes = downloadedBytes,
                        speed = speed,
                        resumeSupported = resumeSupported
                    )
                )
            }

            when (outcome) {
                is HttpDownloadCoordinator.Outcome.Success -> {
                    metrics.recordCompleted()
                    storage.move(finalPartialPath, finalDestination)
                    runCatching { storage.delete(storage.getResumeStateFile(finalDestination).toPath()) }
                    logger.info("HTTP download completed: {}", finalDestination)
                    send(
                        updateTask(
                            task = labelled,
                            request = resolved,
                            state = DownloadState.Completed,
                            totalBytes = outcome.totalBytes ?: outcome.downloadedBytes,
                            downloadedBytes = outcome.downloadedBytes,
                            progress = 1f,
                            resumeSupported = resumeSupported
                        )
                    )
                }

                is HttpDownloadCoordinator.Outcome.Failure -> {
                    metrics.recordFailed()
                    send(
                        updateTask(
                            task = labelled,
                            request = resolved,
                            state = DownloadState.Failed,
                            totalBytes = totalBytes,
                            downloadedBytes = downloadedBytes,
                            error = outcome.error,
                            resumeSupported = resumeSupported
                        )
                    )
                }
            }
        }

    private fun executeTorrent(request: DownloadRequest.Torrent, task: DownloadTask): Flow<DownloadTask> =
        channelFlow {
            var current = task
            val settings = settingsRepository.settings.value
            // No conflict resolution here, unlike HTTP: a torrent writes straight into its folder, so
            // from the first moment of its first attempt "the destination exists" describes this
            // download rather than a rival one. Deciding the name there would rename a download every
            // time it is resumed. The collision is settled when the download is created instead, by
            // org.blaze.service.mapping.RequestMapper.claimDestination.
            val networkClient = TorrentNetworkClient(
                maxPeerConnections = settings.maxPeerConnections,
                enableSeeding = settings.enableSeeding,
                seedTimeLimitMinutes = settings.seedTimeLimitMinutes,
                unlimitedSeeding = settings.unlimitedSeeding,
                seedRatioLimit = settings.seedRatioLimit
            )
            networkClient.download(request).collect { event ->
                when (event) {
                    is TorrentNetworkEvent.MetadataResolved -> {
                        event.metadataBytes?.let { onMetadataResolved(current.id, it) }
                        current = updateTask(
                            task = current,
                            request = current.request,
                            state = DownloadState.Downloading,
                            totalBytes = event.totalBytes,
                            downloadedBytes = current.downloadedBytes,
                            name = displayNameFor(current.request.destination, event.name),
                            files = event.files,
                            // A torrent resumes from its own piece bitmap, independently of any
                            // server header, so the answer is known from the very first metadata.
                            resumeSupported = true
                        )
                        send(current)
                    }

                    is TorrentNetworkEvent.Progress -> {
                        val downloadState = when {
                            event.piecesTotal == 0 -> DownloadState.ResolvingMetadata
                            event.piecesRemaining > 0 -> DownloadState.Downloading
                            else -> DownloadState.Seeding
                        }
                        val calculatedDownloaded = if (event.piecesTotal > 0 && event.totalBytes > 0) {
                            (event.piecesComplete.toDouble() / event.piecesTotal * event.totalBytes).toLong()
                        } else {
                            event.downloadedBytes
                        }
                        val total = if (event.totalBytes > 0) event.totalBytes else null
                        val progress = if (event.piecesTotal > 0) event.piecesComplete.toFloat() / event.piecesTotal else 0f

                        current = updateTask(
                            task = current,
                            request = current.request,
                            state = downloadState,
                            totalBytes = total,
                            downloadedBytes = calculatedDownloaded,
                            speed = event.downloadSpeed,
                            progress = progress,
                            resumeSupported = true
                        ).copy(
                            uploadSpeed = event.uploadSpeed,
                            peers = event.peers
                        )
                        send(current)
                    }

                    is TorrentNetworkEvent.Completed -> {
                        val total = current.totalBytes ?: current.downloadedBytes
                        current = updateTask(
                            task = current,
                            request = current.request,
                            state = DownloadState.Completed,
                            totalBytes = total,
                            downloadedBytes = current.downloadedBytes,
                            progress = 1f
                        )
                        send(current)
                    }

                    is TorrentNetworkEvent.Error -> {
                        current = updateTask(
                            task = current,
                            request = current.request,
                            state = DownloadState.Failed,
                            totalBytes = current.totalBytes,
                            downloadedBytes = current.downloadedBytes,
                            error = event.error
                        )
                        send(current)
                    }
                }
            }
        }

    private fun updateTask(
        task: DownloadTask,
        request: DownloadRequest,
        state: DownloadState,
        totalBytes: Long?,
        downloadedBytes: Long,
        speed: Long = 0,
        progress: Float? = null,
        error: DownloadError? = null,
        name: String? = null,
        files: List<DownloadFileMetadata>? = null,
        resumeSupported: Boolean? = null
    ): DownloadTask {
        val total = totalBytes?.takeIf { it > 0 }
        val calculatedProgress = progress ?: run {
            if (total != null) (downloadedBytes.toDouble() / total).toFloat() else 0f
        }
        val eta = if (total != null && total > downloadedBytes && speed > 0) {
            ((total - downloadedBytes) / speed).seconds
        } else null

        return task.copy(
            request = request,
            name = name ?: task.name,
            state = state,
            totalBytes = total,
            downloadedBytes = downloadedBytes,
            downloadSpeed = speed,
            progress = calculatedProgress,
            eta = eta,
            error = error,
            completedAt = if (state == DownloadState.Completed) Instant.now() else task.completedAt,
            files = files ?: task.files,
            resumeSupported = resumeSupported ?: task.resumeSupported
        )
    }

    private fun resolveDestination(destination: Path, behavior: FileConflictBehavior): Pair<Path?, Path> {
        if (!storage.exists(destination)) {
            return destination to storage.getPartialFile(destination).toPath()
        }
        return when (behavior) {
            FileConflictBehavior.SKIP -> {
                logger.info("Destination {} already exists; skipping the download", destination)
                null to storage.getPartialFile(destination).toPath()
            }
            FileConflictBehavior.OVERWRITE -> {
                storage.delete(destination)
                val partial = storage.getPartialFile(destination).toPath()
                storage.delete(partial)
                storage.delete(storage.getResumeStateFile(destination).toPath())
                destination to partial
            }
            FileConflictBehavior.RENAME, FileConflictBehavior.ASK -> {
                val fullStr = destination.fileName.toString()
                val baseName = fullStr.substringBeforeLast(".")
                val extension = if (fullStr.contains(".")) fullStr.substringAfterLast(".") else ""
                val extStr = if (extension.isNotEmpty()) ".$extension" else ""
                var count = 1
                var newFile = destination.resolveSibling(CopyName.of(baseName, count) + extStr)
                while (storage.exists(newFile)) {
                    count++
                    newFile = destination.resolveSibling(CopyName.of(baseName, count) + extStr)
                }
                logger.info("Destination {} already exists; saving as {}", destination, newFile)
                newFile to storage.getPartialFile(newFile).toPath()
            }
        }
    }

    /**
     * Exponentially weighted speed over the samples the coordinator reports. Sampling on the
     * callback stream (instead of on a timer) keeps the number honest for short transfers,
     * where a fixed 500 ms window would report 0 for most of their lifetime.
     */
    private class HttpProgress {
        private var lastSampleNs = System.nanoTime()
        private var lastBytes = 0L
        private var smoothed = 0.0

        @Synchronized
        fun sample(downloadedBytes: Long): Long {
            val now = System.nanoTime()
            val elapsedNs = now - lastSampleNs
            if (elapsedNs < MIN_SAMPLE_INTERVAL_NS) return smoothed.toLong().coerceAtLeast(0L)

            val delta = downloadedBytes - lastBytes
            if (delta < 0) {
                // A restart from byte 0 (server ignored our range): drop the stale baseline.
                lastBytes = downloadedBytes
                lastSampleNs = now
                return smoothed.toLong().coerceAtLeast(0L)
            }
            val instant = delta * 1_000_000_000.0 / elapsedNs
            smoothed = if (smoothed == 0.0) instant else ALPHA * instant + (1 - ALPHA) * smoothed
            lastBytes = downloadedBytes
            lastSampleNs = now
            return smoothed.toLong().coerceAtLeast(0L)
        }

        private companion object {
            const val ALPHA = 0.3
            const val MIN_SAMPLE_INTERVAL_NS = 200_000_000L
        }
    }
}

/**
 * The label a torrent gets once its metainfo is known.
 *
 * The metainfo's own name is the best title a download can have, and it replaces the guess the source
 * forced - a bare magnet, a numeric URL, a `file.torrent` nobody named. One guess it must not replace:
 * the numbered folder Blaze gave this download because the name it wanted was already taken. Undoing
 * that would leave two rows reading identically in the list while the files sit in folders that do not,
 * which is indistinguishable from the rename never having happened.
 */
internal fun displayNameFor(destination: Path, metainfoName: String): String {
    val folder = destination.fileName?.toString() ?: return metainfoName
    return if (CopyName.attemptOf(folder, metainfoName) != null) folder else metainfoName
}
