package org.blaze.engine.network

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareHead
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.settings.DEFAULT_USER_AGENT
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Thin, allocation-light HTTP transfer primitive shared by every HTTP connection worker.
 *
 * It deliberately exposes a callback sink instead of a `Flow<ByteArray>`: the caller writes the
 * buffer straight into the file channel, so a downloaded block is copied exactly once
 * (socket -> buffer -> disk) instead of being wrapped in a per-read event object.
 */
class HttpNetworkClient(
    private val client: HttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val maxRedirects: Int = 5,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER
) {
    private val logger = LoggerFactory.getLogger(HttpNetworkClient::class.java)

    /** What the server supports, discovered before a transfer is split into parallel workers. */
    data class Probe(
        val status: Int,
        val totalBytes: Long, // -1 when the server did not say
        val acceptsRanges: Boolean,
        val validator: String? // ETag / Last-Modified, used to reject stale resume state
    ) {
        val knownSize: Boolean get() = totalBytes > 0
    }

    /** Headers of the response that will actually carry body bytes. */
    data class Head(
        val status: Int,
        val bodyLength: Long, // bytes this response will deliver (-1 when chunked/unknown)
        val totalBytes: Long, // whole-file size when known (-1 otherwise)
        val resumed: Boolean, // 206 Partial Content
        val validator: String?
    )

    sealed interface StreamResult {
        data class Success(val received: Long) : StreamResult

        /**
         * We asked for a range and the server answered 200 with the whole body. Writing it at the
         * requested offset would shift every byte and silently corrupt the file, so the caller has
         * to restart from offset 0 instead.
         */
        data object RangeIgnored : StreamResult

        /** The body ended before the expected number of bytes arrived. */
        data class Truncated(val received: Long, val expected: Long) : StreamResult

        data class Failed(val error: DownloadError) : StreamResult

        /** Internal: the request has to be replayed against another URL. Never returned by [stream]. */
        data class Redirected(val to: String) : StreamResult
    }

    /**
     * Cheap capability probe that decides between a single stream and a segmented transfer.
     *
     * `HEAD` is tried first because it carries no body; a `Range: bytes=0-0` GET then *proves*
     * ranged reads instead of trusting the (sometimes bogus, sometimes CDN-stripped)
     * `Accept-Ranges` advertisement.
     */
    suspend fun probe(request: DownloadRequest.Http): Probe {
        var url = normalize(request.url)
        var redirects = 0

        while (true) {
            val head = runCatching { headRequest(request, url) }
                .getOrElse { return if (it is CancellationException) throw it else Probe(0, -1, false, null) }

            if (head.status in 300..399) {
                val next = resolveRedirect(url, head.rawHeaders[HttpHeaders.Location])
                if (next == null || redirects++ >= maxRedirects) {
                    // Without a usable target the probe tells nothing; the transfer attempt will
                    // surface the real error.
                    return Probe(head.status, -1, false, null)
                }
                url = next
                continue
            }

            val validator = validator(head.rawHeaders)
            if (head.statusUsable && !head.acceptsRanges) {
                return Probe(head.status, head.totalBytes, false, validator)
            }
            // Verify that ranged reads really work before splitting the file across connections.
            val proved = proveRangeSupport(request, url)
            return if (proved != null) {
                Probe(
                    status = HttpStatusCode.PartialContent.value,
                    totalBytes = maxOf(head.totalBytes, proved.totalBytes),
                    acceptsRanges = true,
                    // A proxy that strips the validator from HEAD must not make two runs of the
                    // same probe disagree about which file the resume state belongs to.
                    validator = validator ?: proved.validator
                )
            } else {
                Probe(head.status, head.totalBytes, false, validator)
            }
        }
    }

    private data class HeadInfo(
        val status: Int,
        val totalBytes: Long,
        val acceptsRanges: Boolean,
        val statusUsable: Boolean,
        val rawHeaders: Headers
    )

    private suspend fun headRequest(request: DownloadRequest.Http, url: String): HeadInfo =
        client.prepareHead(url) { applyCommonHeaders(request) }.execute { response ->
            val headers = response.headers
            HeadInfo(
                status = response.status.value,
                totalBytes = headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    ?: response.contentLength() ?: -1L,
                acceptsRanges = headers[HttpHeaders.AcceptRanges]
                    ?.contains("bytes", ignoreCase = true) == true,
                statusUsable = response.status.isSuccess(),
                rawHeaders = headers
            )
        }

    /** Head of a successful 206 for `bytes=0-0`, or null when ranged reads do not work. */
    private suspend fun proveRangeSupport(request: DownloadRequest.Http, url: String): Head? {
        var captured: Head? = null
        val result = streamAt(
            request = request,
            url = url,
            start = 0,
            end = 0,
            onHead = { head -> if (head.resumed) captured = head },
            sink = { _, _ -> }
        )
        val head = captured ?: return null
        if (result !is StreamResult.Success && result !is StreamResult.Truncated) return null
        return head
    }

    /**
     * Downloads `bytes=[start]-[end]` (open-ended when [end] is null) and hands every buffer read
     * to [sink]. Recoverable problems come back as a [StreamResult]; only cancellation throws.
     */
    suspend fun stream(
        request: DownloadRequest.Http,
        start: Long = 0L,
        end: Long? = null,
        onHead: suspend (Head) -> Unit = {},
        sink: suspend (ByteArray, Int) -> Unit
    ): StreamResult {
        var url = normalize(request.url)
        var redirects = 0
        while (true) {
            val result = streamAt(request, url, start, end, onHead, sink)
            if (result !is StreamResult.Redirected) return result
            if (redirects++ >= maxRedirects) {
                return StreamResult.Failed(DownloadError.NetworkFailure("Too many redirects"))
            }
            url = result.to
            logger.debug("Following redirect {}: {}", redirects, url)
        }
    }

    private suspend fun streamAt(
        request: DownloadRequest.Http,
        url: String,
        start: Long,
        end: Long?,
        onHead: suspend (Head) -> Unit,
        sink: suspend (ByteArray, Int) -> Unit
    ): StreamResult {
        val expected = if (end != null && end >= start) end - start + 1 else null
        val requestedRange = start > 0 || end != null
        val statement = client.prepareGet(url) {
            applyCommonHeaders(request)
            if (requestedRange) header(HttpHeaders.Range, buildRange(start, end))
        }

        return try {
            statement.execute { response ->
                val status = response.status.value
                if (status in 300..399) {
                    val next = resolveRedirect(url, response.headers[HttpHeaders.Location])
                    return@execute if (next == null) {
                        StreamResult.Failed(DownloadError.NetworkFailure("Redirect without a Location header"))
                    } else {
                        StreamResult.Redirected(next)
                    }
                }
                if (!isSuccess(status)) return@execute StreamResult.Failed(errorForStatus(status))
                if (requestedRange && response.status != HttpStatusCode.PartialContent) {
                    // The server ignored the range. Draining the body it offered instead would move
                    // bytes into the wrong place *and* make the capability probe download a file it
                    // is only supposed to measure, so bail out before reading anything.
                    return@execute StreamResult.RangeIgnored
                }

                val head = describe(response)
                onHead(head)

                val channel = response.bodyAsChannel()
                val buffer = ByteArray(readBufferSize)
                var received = 0L
                while (expected == null || received < expected) {
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        sink(buffer, read)
                        received += read
                    }
                }

                val total = expected ?: head.bodyLength
                if (total > 0 && received < total) {
                    return@execute StreamResult.Truncated(received, total)
                }
                StreamResult.Success(received)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.debug("HTTP request to {} failed: {}", url, e.message)
            StreamResult.Failed(e.toDownloadError())
        }
    }

    private fun HttpRequestBuilder.applyCommonHeaders(request: DownloadRequest.Http) {
        if (userAgent.isNotBlank()) header(HttpHeaders.UserAgent, userAgent)
        // Ranged and parallel transfers must see identity bytes: a transformed body breaks both
        // byte offsets and the advertised size.
        header(HttpHeaders.AcceptEncoding, IDENTITY_ENCODING)
        request.headers.forEach { (k, v) -> header(k, v) }
    }

    private fun describe(response: HttpResponse): Head {
        val headers = response.headers
        val contentLength = response.contentLength() ?: -1L
        // Parsed locally rather than through ktor's ContentRange: the "*"/open-ended forms show
        // up in the wild often enough that a defensive parse is worth it, and a bad header must
        // never be able to make us mis-size the file.
        val range = parseContentRange(headers[HttpHeaders.ContentRange])
        return Head(
            status = response.status.value,
            bodyLength = range?.first ?: contentLength,
            totalBytes = range?.second ?: contentLength,
            resumed = response.status == HttpStatusCode.PartialContent,
            validator = validator(headers)
        )
    }

    private fun resolveRedirect(current: String, location: String?): String? {
        if (location == null) return null
        return if (location.contains("://")) location
        else runCatching { URI(current).resolve(location).toString() }.getOrDefault(location)
    }

    private fun normalize(url: String): String = if (url.contains("://")) url else "http://$url"

    private fun validator(headers: Headers): String? =
        headers[HttpHeaders.ETag] ?: headers[HttpHeaders.LastModified]

    private fun isSuccess(status: Int): Boolean = HttpStatusCode.fromValue(status).isSuccess()

    companion object {
        /** 128 KiB amortises syscall cost without making the read path latency-sensitive. */
        const val DEFAULT_READ_BUFFER = 128 * 1024
        const val IDENTITY_ENCODING = "identity"

        /**
         * `bytes 200-1000/2453845` -> body length 801, whole-file length 2453845.
         * Returns null for malformed or `*` forms so callers fall back to Content-Length.
         */
        fun parseContentRange(header: String?): Pair<Long, Long>? {
            val value = header?.substringAfter(' ', header)?.trim() ?: return null
            val slash = value.indexOf('/')
            if (slash <= 0) return null
            val parts = value.substring(0, slash).split('-')
            if (parts.size != 2) return null
            val from = parts[0].trim().toLongOrNull() ?: return null
            val to = parts[1].trim().toLongOrNull() ?: return null
            val total = value.substring(slash + 1).trim().toLongOrNull() ?: return null
            if (to < from || total < to + 1) return null
            return (to - from + 1) to total
        }

        fun buildRange(start: Long, end: Long?): String =
            if (end != null) "bytes=$start-$end" else "bytes=$start-"

        fun errorForStatus(status: Int): DownloadError = when (status) {
            HttpStatusCode.NotFound.value,
            HttpStatusCode.Gone.value -> DownloadError.NotFound

            HttpStatusCode.Unauthorized.value,
            HttpStatusCode.Forbidden.value,
            HttpStatusCode.PaymentRequired.value -> DownloadError.Unauthorized

            HttpStatusCode.RequestedRangeNotSatisfiable.value -> DownloadError.RangeUnsupported

            HttpStatusCode.TooManyRequests.value,
            HttpStatusCode.ServiceUnavailable.value ->
                DownloadError.NetworkFailure("Server throttled the request (HTTP $status)")

            else -> DownloadError.NetworkFailure("Server returned HTTP $status")
        }
    }
}

internal fun Throwable.toDownloadError(): DownloadError {
    if (this is java.net.SocketTimeoutException) return DownloadError.Timeout
    if (this is java.io.IOException && message?.contains("No space left", ignoreCase = true) == true) {
        return DownloadError.DiskFull
    }
    return DownloadError.NetworkFailure(message ?: javaClass.simpleName)
}
