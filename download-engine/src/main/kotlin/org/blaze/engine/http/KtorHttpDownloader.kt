package org.blaze.engine.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.core.Downloader
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.FileConflictBehavior
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException as KotlinCancellationException

class KtorHttpDownloader(
    private val request: DownloadRequest.Http,
    private val httpClient: HttpClient? = null,
    private val settingsRepository: EngineSettingsRepository? = null
) : Downloader {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val downloadId = DownloadId(UUID.randomUUID().toString())
    private val createdAt = Instant.now()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var cancelRequested = false

    private val bufferSize = 8192

    override fun download(): Flow<DownloadTask> = channelFlow {
        job = currentCoroutineContext()[Job]
        cancelRequested = false

        val settings = settingsRepository?.settings?.value ?: DownloadSettings()
        var destinationFile = request.destination.toFile()
        var partialFile = request.destination.resolveSibling("${request.destination.fileName}.part").toFile()

        if (destinationFile.exists()) {
            when (settings.fileConflictBehavior) {
                FileConflictBehavior.SKIP -> {
                    send(createTask(DownloadState.Completed, totalBytes = destinationFile.length(), downloadedBytes = destinationFile.length(), progress = 1f))
                    return@channelFlow
                }
                FileConflictBehavior.OVERWRITE -> {
                    withContext(Dispatchers.IO) {
                        destinationFile.delete()
                        partialFile.delete()
                    }
                }
                FileConflictBehavior.RENAME, FileConflictBehavior.ASK -> {
                    val fullStr = request.destination.fileName.toString()
                    val baseName = fullStr.substringBeforeLast(".")
                    val extension = if (fullStr.contains(".")) fullStr.substringAfterLast(".") else ""
                    val extStr = if (extension.isNotEmpty()) ".$extension" else ""
                    var count = 1
                    var newFile = request.destination.resolveSibling("$baseName ($count)$extStr").toFile()
                    while (newFile.exists()) {
                        count++
                        newFile = request.destination.resolveSibling("$baseName ($count)$extStr").toFile()
                    }
                    destinationFile = newFile
                    partialFile = newFile.resolveSibling("${newFile.name}.part")
                }
            }
        }

        val client = httpClient ?: HttpClient(CIO) {
            engine {
                maxConnectionsCount = settings.maxConnectionsPerDownload
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
            followRedirects = false
        }

        try {
            send(createTask(DownloadState.Starting))

            var currentUrl = if (!request.url.contains("://")) "http://${request.url}" else request.url
            var redirectCount = 0
            
            while (true) {
                val statement = client.prepareGet(currentUrl) {
                    header(HttpHeaders.UserAgent, userAgent)
                    request.headers.forEach { (k, v) -> header(k, v) }
                    if (partialFile.exists() && partialFile.length() > 0) {
                        header(HttpHeaders.Range, "bytes=${partialFile.length()}-")
                    }
                }
                
                var shouldRedirect = false
                var nextUrl: String? = null
                
                statement.execute { currentResponse ->
                    if (currentResponse.status.value in 300..399) {
                        val location = currentResponse.headers[HttpHeaders.Location]
                        if (location != null && redirectCount < 5) {
                            redirectCount++
                            nextUrl = if (location.contains("://")) {
                                location
                            } else {
                                try {
                                    URI(currentUrl).resolve(location).toString()
                                } catch (_: Exception) {
                                    location
                                }
                            }
                            shouldRedirect = true
                        }
                    } else {
                        if (!currentResponse.status.isSuccess()) {
                            val message = "Server returned ${currentResponse.status.value} ${currentResponse.status.description}"
                            if (currentResponse.status !in RETRYABLE_STATUSES) {
                                partialFile.delete()
                            }
                            send(createTask(DownloadState.Failed, DownloadError.NetworkFailure(message)))
                        } else {
                            downloadBody(currentResponse, partialFile, destinationFile)
                        }
                    }
                }
                
                if (shouldRedirect && nextUrl != null) {
                    currentUrl = nextUrl
                    continue
                }
                break
            }

        } catch (e: Exception) {
            if (e is KotlinCancellationException) {
                if (cancelRequested) {
                    partialFile.delete()
                }
                send(createTask(DownloadState.Cancelled, downloadedBytes = partialFile.length().takeIf { it > 0 }))
                throw e
            } else {
                send(createTask(DownloadState.Failed, DownloadError.NetworkFailure(e.message ?: "Unknown error")))
            }
        } finally {
            if (httpClient == null) {
                client.close()
            }
        }
    }

    private suspend fun ProducerScope<DownloadTask>.downloadBody(
        response: HttpResponse,
        partialFile: File,
        destinationFile: File
    ) {
        val contentType = response.headers[HttpHeaders.ContentType]
        val lowerName = request.name.lowercase()
        if (contentType != null && contentType.contains("text/html", ignoreCase = true)) {
            if (!lowerName.endsWith(".html") && !lowerName.endsWith(".htm")) {
                send(
                    createTask(
                        DownloadState.Failed,
                        DownloadError.NetworkFailure("Link expired or invalid: received an HTML webpage instead of the requested file. Please generate a fresh download link.")
                    )
                )
                return
            }
        }

        val contentLength = response.contentLength() ?: -1L
        val isResumed = response.status == HttpStatusCode.PartialContent
        val initialDownloaded = if (isResumed) partialFile.length() else 0L
        val actualTotal = if (isResumed && contentLength > 0) contentLength + initialDownloaded else contentLength

        send(
            createTask(
                state = DownloadState.Downloading,
                totalBytes = actualTotal,
                downloadedBytes = initialDownloaded
            )
        )

        val channel = response.bodyAsChannel()
        var downloaded = initialDownloaded

        withContext(Dispatchers.IO) {
            RandomAccessFile(partialFile, "rw").use { raf ->
                if (isResumed) raf.seek(initialDownloaded) else raf.setLength(0)

                val buffer = ByteArray(bufferSize)
                var lastUpdate = System.currentTimeMillis()
                var bytesSinceLastUpdate = 0L
                var smoothedSpeed = 0.0

                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break

                    raf.write(buffer, 0, read)
                    downloaded += read
                    bytesSinceLastUpdate += read

                    val liveSettings = settingsRepository?.settings?.value
                    if (liveSettings?.globalSpeedLimitEnabled == true) {
                        val limitBytesPerMs = (liveSettings.globalSpeedLimitKbps * 1024) / 1000.0
                        if (limitBytesPerMs > 0) {
                            val elapsedFromStart = System.currentTimeMillis() - lastUpdate + 1
                            val maxAllowedBytes = elapsedFromStart * limitBytesPerMs
                            if (bytesSinceLastUpdate > maxAllowedBytes) {
                                val sleepMs = ((bytesSinceLastUpdate / limitBytesPerMs) - elapsedFromStart).toLong()
                                if (sleepMs > 0) {
                                    delay(sleepMs.milliseconds)
                                }
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastUpdate
                    if (elapsed >= 500) {
                        val instantSpeed = (bytesSinceLastUpdate * 1000.0) / elapsed
                        smoothedSpeed = if (smoothedSpeed == 0.0) instantSpeed
                        else (0.3 * instantSpeed) + (0.7 * smoothedSpeed)

                        send(
                            createTask(
                                state = DownloadState.Downloading,
                                totalBytes = actualTotal,
                                downloadedBytes = downloaded,
                                downloadSpeed = smoothedSpeed.toLong()
                            )
                        )

                        lastUpdate = now
                        bytesSinceLastUpdate = 0
                    }
                }
            }
        }

        val complete = actualTotal <= 0 || downloaded >= actualTotal
        if (complete) {
            withContext(Dispatchers.IO) {
                Files.move(partialFile.toPath(), destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            send(createTask(DownloadState.Completed, totalBytes = downloaded, downloadedBytes = downloaded, progress = 1f))
        } else {
            send(
                createTask(
                    DownloadState.Failed,
                    error = DownloadError.NetworkFailure("Connection closed before download finished ($downloaded / $actualTotal)"),
                    totalBytes = actualTotal,
                    downloadedBytes = downloaded
                )
            )
        }
    }

    private fun createTask(
        state: DownloadState,
        error: DownloadError? = null,
        totalBytes: Long? = null,
        downloadedBytes: Long? = null,
        downloadSpeed: Long = 0,
        progress: Float? = null
    ): DownloadTask {
        val total = totalBytes?.takeIf { it > 0 }
        val done = downloadedBytes ?: 0L
        val calculatedProgress = progress ?: run {
            if (total != null) (done.toDouble() / total).toFloat() else 0f
        }
        val eta = if (total != null && total > done && downloadSpeed > 0) {
            ((total - done) / downloadSpeed).seconds
        } else null

        return DownloadTask(
            id = downloadId,
            name = request.name,
            request = request,
            state = state,
            totalBytes = total,
            downloadedBytes = done,
            downloadSpeed = downloadSpeed,
            progress = calculatedProgress,
            eta = eta,
            error = error,
            createdAt = createdAt,
            completedAt = if (state == DownloadState.Completed) Instant.now() else null
        )
    }

    override suspend fun pause() {
        cancelRequested = false
        job?.cancel()
    }

    override suspend fun cancel() {
        cancelRequested = true
        job?.cancel()
    }

    companion object {
        private val RETRYABLE_STATUSES = setOf(
            HttpStatusCode.InternalServerError,
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.GatewayTimeout
        )
    }
}
