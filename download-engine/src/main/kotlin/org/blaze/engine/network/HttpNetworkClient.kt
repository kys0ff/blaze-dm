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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import java.net.URI

class HttpNetworkClient(
    private val client: HttpClient,
    private val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
) {
    fun download(request: DownloadRequest.Http, offset: Long = 0L): Flow<HttpNetworkEvent> = channelFlow {
        var currentUrl = if (!request.url.contains("://")) "http://${request.url}" else request.url
        var redirectCount = 0

        while (true) {
            val statement = client.prepareGet(currentUrl) {
                header(HttpHeaders.UserAgent, userAgent)
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
                    } else if (!response.status.isSuccess()) {
                        val message = "Server returned ${response.status.value} ${response.status.description}"
                        send(HttpNetworkEvent.Error(DownloadError.NetworkFailure(message)))
                    } else {
                        val contentLength = response.contentLength() ?: -1L
                        val isResumed = response.status == HttpStatusCode.PartialContent
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
                    }
                }
            } catch (e: Exception) {
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