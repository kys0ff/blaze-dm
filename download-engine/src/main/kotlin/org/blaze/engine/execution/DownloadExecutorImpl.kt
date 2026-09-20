package org.blaze.engine.execution

import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.network.HttpNetworkEvent
import org.blaze.engine.network.TorrentNetworkClient
import org.blaze.engine.network.TorrentNetworkEvent
import org.blaze.engine.retry.RetryPolicy
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.engine.storage.FileStorage
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DownloadExecutorImpl(
    private val initialTask: DownloadTask,
    private val httpClient: HttpClient,
    private val storage: FileStorage,
    private val settingsRepository: EngineSettingsRepository,
    private val retryPolicy: RetryPolicy,
    private val onMetadataResolved: (DownloadId, ByteArray) -> Unit
) : DownloadExecutor {
    private val logger = LoggerFactory.getLogger(DownloadExecutorImpl::class.java)
    private var retryCount = 0

    override fun execute(): Flow<DownloadTask> = channelFlow {
        var currentTask = initialTask
        send(currentTask)

        while (true) {
            try {
                val flow = when (val request = currentTask.request) {
                    is DownloadRequest.Http -> executeHttp(request, currentTask)
                    is DownloadRequest.Torrent -> executeTorrent(request, currentTask)
                }

                flow.collect { updatedTask ->
                    currentTask = updatedTask
                    send(updatedTask)
                }

                if (currentTask.state == DownloadState.Completed) break
                if (currentTask.state == DownloadState.Cancelled) break
                if (currentTask.state == DownloadState.Failed) {
                    val delayMs = retryPolicy.getNextDelay(currentTask.error!!, retryCount)
                    if (delayMs != null) {
                        retryCount++
                        logger.info("Retrying download ${currentTask.id} in ${delayMs}ms (retry $retryCount)")
                        send(currentTask.copy(state = DownloadState.Queued))
                        delay(delayMs.milliseconds)
                        continue
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                logger.error("Unexpected error in DownloadExecutor for ${currentTask.id}", e)
                break
            }
        }
    }

    private fun executeHttp(request: DownloadRequest.Http, task: DownloadTask): Flow<DownloadTask> =
        channelFlow {
            val settings = settingsRepository.settings.value
            val destination = request.destination

            val (finalDestination, finalPartialPath) = resolveDestination(
                destination,
                settings.fileConflictBehavior
            )
            if (finalDestination == null) {
                // Skipped
                send(
                    task.copy(
                        state = DownloadState.Completed,
                        downloadedBytes = storage.size(destination),
                        totalBytes = storage.size(destination),
                        progress = 1f
                    )
                )
                return@channelFlow
            }

            val networkClient = HttpNetworkClient(httpClient)
            val offset =
                if (storage.exists(finalPartialPath)) storage.size(finalPartialPath) else 0L

            var totalBytes = task.totalBytes
            var downloadedBytes = offset
            var lastUpdate = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var smoothedSpeed = 0.0

            networkClient.download(request, offset).collect { event ->
                when (event) {
                    is HttpNetworkEvent.Headers -> {
                        val actualTotal =
                            if (event.isResumed && event.contentLength > 0) event.contentLength + offset else event.contentLength
                        totalBytes = actualTotal
                        send(
                            updateTask(
                                task,
                                DownloadState.Downloading,
                                totalBytes,
                                downloadedBytes
                            )
                        )
                    }

                    is HttpNetworkEvent.Chunk -> {
                        storage.openForWrite(finalPartialPath).use { session ->
                            session.seek(downloadedBytes)
                            session.write(event.data, 0, event.length)
                        }
                        downloadedBytes += event.length
                        bytesSinceLastUpdate += event.length

                        applySpeedLimit(
                            bytesSinceLastUpdate,
                            lastUpdate,
                            settingsRepository.settings.value
                        )

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 500) {
                            smoothedSpeed = calculateSmoothedSpeed(
                                bytesSinceLastUpdate,
                                now - lastUpdate,
                                smoothedSpeed
                            )
                            send(
                                updateTask(
                                    task,
                                    DownloadState.Downloading,
                                    totalBytes,
                                    downloadedBytes,
                                    smoothedSpeed.toLong()
                                )
                            )
                            lastUpdate = now
                            bytesSinceLastUpdate = 0
                        }
                    }

                    is HttpNetworkEvent.Completed -> {
                        storage.move(finalPartialPath, finalDestination)
                        send(
                            updateTask(
                                task,
                                DownloadState.Completed,
                                downloadedBytes,
                                downloadedBytes,
                                progress = 1f
                            )
                        )
                    }

                    is HttpNetworkEvent.Error -> {
                        send(
                            updateTask(
                                task,
                                DownloadState.Failed,
                                totalBytes,
                                downloadedBytes,
                                error = event.error
                            )
                        )
                    }
                }
            }
        }

    private fun executeTorrent(request: DownloadRequest.Torrent, task: DownloadTask): Flow<DownloadTask> =
        channelFlow {
            var current = task
            val networkClient = TorrentNetworkClient()
            networkClient.download(request).collect { event ->
                when (event) {
                    is TorrentNetworkEvent.MetadataResolved -> {
                        event.metadataBytes?.let { onMetadataResolved(current.id, it) }
                        current = updateTask(
                            current,
                            DownloadState.Downloading,
                            event.totalBytes,
                            current.downloadedBytes,
                            name = event.name,
                            files = event.files
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
                            current,
                            downloadState,
                            total,
                            calculatedDownloaded,
                            event.downloadSpeed,
                            progress
                        ).copy(
                            uploadSpeed = event.uploadSpeed,
                            peers = event.peers
                        )
                        send(current)
                    }

                    is TorrentNetworkEvent.Completed -> {
                        val total = current.totalBytes ?: current.downloadedBytes
                        current = updateTask(current, DownloadState.Completed, total, current.downloadedBytes, progress = 1f)
                        send(current)
                    }

                    is TorrentNetworkEvent.Error -> {
                        current = updateTask(
                            current,
                            DownloadState.Failed,
                            current.totalBytes,
                            current.downloadedBytes,
                            error = event.error
                        )
                        send(current)
                    }
                }
            }
        }

    private fun updateTask(
        task: DownloadTask,
        state: DownloadState,
        totalBytes: Long?,
        downloadedBytes: Long,
        speed: Long = 0,
        progress: Float? = null,
        error: DownloadError? = null,
        name: String? = null,
        files: List<DownloadFileMetadata>? = null
    ): DownloadTask {
        val total = totalBytes?.takeIf { it > 0 }
        val calculatedProgress = progress ?: run {
            if (total != null) (downloadedBytes.toDouble() / total).toFloat() else 0f
        }
        val eta = if (total != null && total > downloadedBytes && speed > 0) {
            ((total - downloadedBytes) / speed).seconds
        } else null

        return task.copy(
            name = name ?: task.name,
            state = state,
            totalBytes = total,
            downloadedBytes = downloadedBytes,
            downloadSpeed = speed,
            progress = calculatedProgress,
            eta = eta,
            error = error,
            completedAt = if (state == DownloadState.Completed) Instant.now() else task.completedAt,
            files = files ?: task.files
        )
    }

    private suspend fun applySpeedLimit(bytes: Long, lastUpdate: Long, settings: DownloadSettings) {
        if (settings.globalSpeedLimitEnabled) {
            val limitBytesPerMs = (settings.globalSpeedLimitKbps * 1024) / 1000.0
            if (limitBytesPerMs > 0) {
                val elapsed = System.currentTimeMillis() - lastUpdate + 1
                val maxAllowed = elapsed * limitBytesPerMs
                if (bytes > maxAllowed) {
                    val sleepMs = ((bytes / limitBytesPerMs) - elapsed).toLong()
                    if (sleepMs > 0) delay(sleepMs.milliseconds)
                }
            }
        }
    }

    private fun resolveDestination(destination: Path, behavior: FileConflictBehavior): Pair<Path?, Path> {
        if (!storage.exists(destination)) {
            return destination to storage.getPartialFile(destination).toPath()
        }
        return when (behavior) {
            FileConflictBehavior.SKIP -> null to storage.getPartialFile(destination).toPath()
            FileConflictBehavior.OVERWRITE -> {
                storage.delete(destination)
                val partial = storage.getPartialFile(destination).toPath()
                storage.delete(partial)
                destination to partial
            }
            FileConflictBehavior.RENAME, FileConflictBehavior.ASK -> {
                val fullStr = destination.fileName.toString()
                val baseName = fullStr.substringBeforeLast(".")
                val extension = if (fullStr.contains(".")) fullStr.substringAfterLast(".") else ""
                val extStr = if (extension.isNotEmpty()) ".$extension" else ""
                var count = 1
                var newFile = destination.resolveSibling("$baseName ($count)$extStr")
                while (storage.exists(newFile)) {
                    count++
                    newFile = destination.resolveSibling("$baseName ($count)$extStr")
                }
                newFile to storage.getPartialFile(newFile).toPath()
            }
        }
    }

    private fun calculateSmoothedSpeed(bytes: Long, elapsedMs: Long, currentSmoothed: Double): Double {
        val instantSpeed = (bytes * 1000.0) / elapsedMs
        return if (currentSmoothed == 0.0) instantSpeed else (0.3 * instantSpeed) + (0.7 * currentSmoothed)
    }
}