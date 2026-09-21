package org.blaze.engine.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.settings.DEFAULT_USER_AGENT
import org.slf4j.LoggerFactory
import java.net.URI

class HttpNetworkClient(
    private val client: HttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val maxRedirects: Int = 5
) {
    private val logger = LoggerFactory.getLogger(HttpNetworkClient::class.java)

    fun download(request: DownloadRequest.Http, offset: Long = 0L): Flow<HttpNetworkEvent> = channelFlow {
        var currentUrl = if (!request.url.contains("://")) "http://${request.url}" else request.url
        var redirectCount = 0
        logger.debug("Starting HTTP download from {} (offset={} bytes)", currentUrl, offset)

        while (true) {
            val statement = client.prepareGet(currentUrl) {
                if (userAgent.isNotBlank()) header(HttpHeaders.UserAgent, userAgent)
                request.headers.forEach { (k, v) -> header(k, v) }
                if (offset > 0) {
                    header(HttpHeaders.Range, "bytes=$offset-")
                }
            }

            var shouldRedirect = false
            var nextUrl: String? = null

            try {
                statement.execute { response ->
                    if (response.status.value in 300..399) {
                        val location = response.headers[HttpHeaders.Location]
                        if (location != null && redirectCount < maxRedirects) {
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
                            logger.debug(
                                "Following redirect {}/{}: {} -> {}",
                                redirectCount, maxRedirects, currentUrl, nextUrl
                            )
                        } else if (location == null) {
                            logger.warn("Got {} redirect status without a Location header", response.status.value)
                            send(HttpNetworkEvent.Error(DownloadError.NetworkFailure("Redirect without location")))
                        } else {
                            logger.warn("Redirect limit reached ({}); giving up", maxRedirects)
                            send(HttpNetworkEvent.Error(DownloadError.NetworkFailure("Too many redirects")))
                        }
                    } else if (!response.status.isSuccess()) {
                        val message = "Server returned ${response.status.value} ${response.status.description}"
                        logger.warn("HTTP download failed for {}: {}", currentUrl, message)
                        send(HttpNetworkEvent.Error(DownloadError.NetworkFailure(message)))
                    } else {
                        val contentLength = response.contentLength() ?: -1L
                        val isResumed = response.status == HttpStatusCode.PartialContent
                        if (isResumed) {
                            logger.debug("Server accepted the resume (206 Partial Content) for {}", currentUrl)
                        }
                        send(HttpNetworkEvent.Headers(isResumed, contentLength))

                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(8192)
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer)
                            if (read == -1) break
                            if (read > 0) {
                                send(HttpNetworkEvent.Chunk(buffer.copyOfRange(0, read), read))
                            }
                        }
                        send(HttpNetworkEvent.Completed)
                        logger.debug("HTTP stream finished for {}", currentUrl)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.error("HTTP request to {} failed", currentUrl, e)
                send(HttpNetworkEvent.Error(DownloadError.NetworkFailure(e.message ?: "Unknown network error")))
            }

            if (shouldRedirect && nextUrl != null) {
                currentUrl = nextUrl!!
                continue
            }
            break
        }
    }
}