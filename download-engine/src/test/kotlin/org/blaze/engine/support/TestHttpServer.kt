package org.blaze.engine.support

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/**
 * Minimal loopback HTTP/1.1 server for exercising the transfer paths.
 *
 * It exists so the tests assert on real socket behaviour (status lines, `Range`, `Content-Range`,
 * early EOF) instead of a mocked client that would happily accept the bugs those tests target.
 * Every knob models a failure mode seen in the wild.
 *
 * Knobs split into two groups:
 *  - Constructor params describe the resource itself (payload, ranges, keep-alive, throttling).
 *  - `@Volatile var` fields are per-request fault injection that a test can flip between attempts
 *    (429/503 + `Retry-After`, ETag swap, HEAD lying about `Content-Length`, redirects, gzip,
 *    `Content-Disposition`, per-IP connection limit, clean truncation, multi-range handling).
 *
 * Fault injection is *only* applied to ranged bodies longer than one byte and to non-HEAD whole-body
 * GETs. The engine's `HEAD` + `bytes=0-0` probe is left intact so a test of "the transfer should
 * retry" is a test of the transfer, not of the probe being confused.
 */
class TestHttpServer(
    private val payload: ByteArray,
    private val supportsRanges: Boolean = true,
    /** Mutable so a test can fail the first attempt and let the resume succeed. */
    @Volatile var earlyCloseAfterBytes: Long = Long.MAX_VALUE,
    private val perConnectionBytesPerSec: Long = 0L,
    private val headAllowed: Boolean = true,
    /**
     * Value of the `Accept-Ranges` header: "bytes", "none", or null to send no header at all.
     * Servers omit it constantly (advertising is optional in RFC 9110), and a client must read that
     * silence as "unknown" - which is a different answer from the outright refusal `none` means.
     */
    private val acceptRangesHeader: String? = "bytes",
    private val validator: String? = null,
    /**
     * How many ranged bodies are cut short after a third of their length. Models the flaky host
     * that drops *some* connections while the rest of the transfer is fine - the download has to
     * recover by re-reading those chunks, not by failing.
     */
    private val cutFirstBodies: Int = 0,
    /**
     * When true the server keeps the TCP connection open across requests (`Connection: keep-alive`)
     * and serves several requests per socket, the way a real HTTP/1.1 server does. Lets a test
     * distinguish "the client opened one socket and reused it" from "a fresh socket per chunk",
     * which is where the connection-establishment cost of a segmented transfer lives on a WAN.
     */
    private val keepAlive: Boolean = false,
    /**
     * The first N ranged bodies are written as headers and then *hang* (deliver no body bytes)
     * for [hangMillis]. Models a connection that is neither fast nor cleanly dead - the exact case
     * the stalled-worker watchdog must catch while other connections keep making progress.
     */
    @Volatile var hangFirstBodies: Int = 0,
    @Volatile var hangMillis: Long = 15_000L,
    /**
     * Requests whose byte range reaches [slowTailFromByte] are served at [slowTailBytesPerSec]
     * while every other range runs at full speed. This is the deterministic "one connection is far
     * slower than the rest" tail a segmented download can end on, used to measure (and then bound)
     * tail latency instead of guessing about it.
     */
    @Volatile var slowTailFromByte: Long = -1L,
    @Volatile var slowTailBytesPerSec: Long = 0L,
    /**
     * Wall-clock time each freshly accepted TCP connection "costs" before its first response - a
     * stand-in for the round-trip expense of connection establishment (TCP handshake plus, on a real
     * WAN, a TLS handshake). Charged once per socket, so a keep-alive client that reuses sockets pays
     * it a handful of times while a `Connection: close` client pays it on every chunk request.
     */
    @Volatile var connectDelayMillis: Long = 0L
) : AutoCloseable {

    // ---- Fault-injection knobs (settable after construction so a test can flip a mid-transfer) ----

    /**
     * First [failFirstN] *data-plane* requests (ranged GET with length > 1, or non-HEAD whole-body
     * GET) respond with this status instead of 200/206. Zero disables injection. HEAD and the
     * `bytes=0-0` probe are never injected so a test of retry semantics measures the retry, not a
     * broken probe.
     */
    @Volatile var failStatus: Int = 0
    @Volatile var failFirstN: Int = 0
    /** Raw value of the `Retry-After` header emitted with [failStatus]; null = send no header. */
    @Volatile var retryAfter: String? = null

    /**
     * Once more than [etagSwapAfterRequests] requests have been served, the response `ETag` becomes
     * [etagAfterSwap] instead of [validator]. Models the "content rotated between pause and resume"
     * case. Set [etagSwapAfterRequests] negative to disable.
     */
    @Volatile var etagSwapAfterRequests: Int = -1
    @Volatile var etagAfterSwap: String? = null

    /** Request path → 3xx target. When the requested path is a key, respond with the redirect. */
    @Volatile var redirects: Map<String, Redirect> = emptyMap()

    /** Value for the `Content-Disposition` header, when set (emitted on 200 and 206). */
    @Volatile var contentDisposition: String? = null

    /** HEAD replies carry this `Content-Length` instead of the payload's real size. */
    @Volatile var headContentLengthLie: Long? = null

    /**
     * Refuse more than this many *concurrently open* TCP connections by accepting the socket and
     * closing it immediately. Models the per-IP cap that CDN edge nodes enforce (typically 64 or 128
     * sockets) which is otherwise invisible on loopback.
     */
    @Volatile var maxConcurrentConnections: Int = Int.MAX_VALUE

    /**
     * Non-ranged GET bodies are gzip-compressed and flagged with `Content-Encoding: gzip`,
     * *regardless* of the request's `Accept-Encoding`. That is the "server ignored our identity
     * header" quirk the engine must detect and either honour or refuse on.
     */
    @Volatile var gzipNonRanged: Boolean = false

    /**
     * First N ranged bodies advertise the full `Content-Length` in the header and then close the
     * socket after delivering only ~⅔ of the promised bytes. Unlike [cutFirstBodies] there is no
     * exception; the stream just ends. A correct client must treat this as truncation, not success.
     */
    @Volatile var cleanTruncateBodies: Int = 0

    /**
     * Multi-range request handling:
     *  - [MultiRange.WholeBody] (default) answers `bytes=a-b,c-d` with a 200 whole body - RFC 7233
     *    §3 explicitly permits this, and it is what most CDNs do.
     *  - [MultiRange.Multipart] answers with a real 206 `multipart/byteranges` body.
     *  - [MultiRange.Reject] answers with 416.
     */
    @Volatile var multiRange: MultiRange = MultiRange.WholeBody

    enum class MultiRange { WholeBody, Multipart, Reject }

    data class Redirect(val status: Int, val location: String)

    // ---- Observability ----

    /** Every `Range` header received, in order, including the empty string for rangeless GETs. */
    val rangeRequests: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /** Every `If-Range` header received, in order; empty string when the client sent none. */
    val ifRangeRequests: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /** Every request line path, in order (query stripped). */
    val requestPaths: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /** Total body bytes pushed at clients, the cheapest way to prove a resume did not re-download. */
    val bytesServed = AtomicLong(0)
    val requestCount = AtomicLong(0)

    /** Accepted TCP sockets; compare against [requestCount] to prove or disprove connection reuse. */
    val connectionCount = AtomicInteger(0)
    private val bodiesServed = AtomicInteger(0)
    private val hangsUsed = AtomicInteger(0)
    private val failUsed = AtomicInteger(0)
    private val truncatedBodiesUsed = AtomicInteger(0)
    private val openConnections = AtomicInteger(0)

    /** Concurrently-served requests, peaked; used to prove the pool really runs N ways. */
    private val inflight = AtomicInteger(0)
    val maxConcurrentRequests = AtomicInteger(0)

    /** Paths seen that were served as redirects (for tests asserting a client followed them). */
    val redirectCount = AtomicInteger(0)

    /** Per-IP connection refusals triggered by [maxConcurrentConnections]. */
    val refusedConnections = AtomicInteger(0)

    /** Ranged requests downgraded to a whole-body 200 because their `If-Range` validator had gone stale. */
    val ifRangeMismatchServed = AtomicInteger(0)

    val port: Int get() = server.localPort
    val url: String get() = "http://127.0.0.1:$port/file.bin"

    private val server = ServerSocket(0)
    private val running = AtomicBoolean(true)
    private val accepting = Thread { acceptLoop() }.apply { isDaemon = true; name = "test-http-accept" }

    init {
        accepting.start()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            val opened = openConnections.incrementAndGet()
            if (opened > maxConcurrentConnections) {
                refusedConnections.incrementAndGet()
                openConnections.decrementAndGet()
                runCatching { socket.close() }
                continue
            }
            connectionCount.incrementAndGet()
            Thread {
                try {
                    handle(socket)
                } finally {
                    openConnections.decrementAndGet()
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        if (connectDelayMillis > 0) {
            try {
                Thread.sleep(connectDelayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        socket.use {
            try {
                val input: InputStream = it.getInputStream()
                val reader = BufferedReader(input.reader(StandardCharsets.ISO_8859_1))
                val out = it.getOutputStream()
                while (running.get()) {
                    val requestLine = reader.readLine() ?: return
                    if (!serveOne(reader, out, requestLine)) return
                }
            } catch (_: Exception) {
                // The client hung up mid-transfer; that is itself a scenario worth testing.
            }
        }
    }

    /** Handles one request. Returns false when the connection must be closed after this request. */
    private fun serveOne(reader: BufferedReader, out: OutputStream, requestLine: String): Boolean {
        val active = inflight.incrementAndGet()
        maxConcurrentRequests.accumulateAndGet(active) { a, b -> maxOf(a, b) }
        try {
            return serveOneInternal(reader, out, requestLine)
        } finally {
            inflight.decrementAndGet()
        }
    }

    private fun serveOneInternal(reader: BufferedReader, out: OutputStream, requestLine: String): Boolean {
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty().uppercase()
        val target = parts.getOrNull(1).orEmpty()
        val path = target.substringBefore('?')
        var range: String? = null
        var ifRange: String? = null
        while (true) {
            val line = reader.readLine() ?: return false
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            if (line.startsWith("If-Range:", ignoreCase = true)) ifRange = line.substringAfter(':').trim()
        }
        requestCount.incrementAndGet()
        requestPaths.add(path)
        rangeRequests.add(range ?: "")
        ifRangeRequests.add(ifRange ?: "")

        val connection = if (keepAlive) "keep-alive" else "close"

        // Redirect handling short-circuits everything else - a 3xx Location is what a browser would
        // see, and the engine must follow it (or refuse to, if that is what we are pinning).
        redirects[path]?.let { redirect ->
            redirectCount.incrementAndGet()
            out.write(
                buildString {
                    append("HTTP/1.1 ").append(redirect.status).append(" Redirect\r\n")
                    append("Location: ").append(redirect.location).append("\r\n")
                    append("Content-Length: 0\r\n")
                    append("Connection: ").append(connection).append("\r\n\r\n")
                }.toByteArray(StandardCharsets.ISO_8859_1)
            )
            out.flush()
            return keepAlive
        }

        // Fault injection: fail the first N *data-plane* requests. HEAD and the 1-byte probe are
        // deliberately spared so tests of retry semantics exercise the retry, not the probe.
        if (failFirstN > 0 && isDataPlaneRequest(method, range)) {
            if (failUsed.getAndIncrement() < failFirstN) {
                writeFailure(out, failStatus, retryAfter, connection)
                return keepAlive
            }
        }

        val liveValidator = currentValidator()

        when {
            method == "HEAD" && !headAllowed -> {
                writeHead(out, "HTTP/1.1 405 Method Not Allowed", -1, liveValidator, connection)
            }

            method == "HEAD" -> {
                val advertised = headContentLengthLie ?: payload.size.toLong()
                writeHead(out, "HTTP/1.1 200 OK", advertised, liveValidator, connection)
            }

            // Multi-range request: honour the configured behaviour. WholeBody (default) answers 200
            // with the entire payload; Multipart returns a real byteranges body; Reject gives 416.
            range != null && supportsRanges && range.isMultiRange() -> {
                when (multiRange) {
                    MultiRange.Reject ->
                        writeHead(out, "HTTP/1.1 416 Requested Range Not Satisfiable", -1, liveValidator, connection)

                    MultiRange.WholeBody -> serveWholeBody(out, connection, liveValidator)

                    MultiRange.Multipart ->
                        serveMultipart(out, range, connection, liveValidator)
                }
            }

            // The dangerous server: it answers a range request with the whole body, which
            // is exactly the case that used to shift every byte and corrupt the file.
            range == null || !supportsRanges -> serveWholeBody(out, connection, liveValidator)

            else -> {
                val (from, to) = parseRange(range, payload.size)
                if (from == null) {
                    writeHead(out, "HTTP/1.1 416 Requested Range Not Satisfiable", -1, liveValidator, connection)
                } else if (ifRange != null && ifRange != liveValidator) {
                    // The `If-Range` validator no longer matches the live representation: per RFC 9110
                    // §14.22 the server ignores the Range and answers with the whole body (200), which
                    // is how a ranged client learns the file rotated under it instead of silently
                    // stitching two representations together.
                    ifRangeMismatchServed.incrementAndGet()
                    serveWholeBody(out, connection, liveValidator)
                } else {
                    val length = to - from + 1
                    write206(out, from, to, length, liveValidator, connection)
                    if (length > 1 && hangsUsed.getAndIncrement() < hangFirstBodies) {
                        // Headers are on the wire, then the socket goes quiet: the client is parked
                        // in a body read that never yields a byte. Never hangs the 1-byte probe.
                        try {
                            Thread.sleep(hangMillis)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        return false
                    }
                    val cut = length > 1 && bodiesServed.getAndIncrement() < cutFirstBodies
                    val cleanTruncate = length > 1 && truncatedBodiesUsed.getAndIncrement() < cleanTruncateBodies
                    val slow = slowTailFromByte in 0 until payload.size.toLong() && to >= slowTailFromByte
                    if (cleanTruncate) {
                        // Advertise the full length but close after only ~2/3 of the bytes; no
                        // exception - the client sees a graceful EOF that lies about its Content-Length.
                        streamBody(
                            out, payload, from, (length * 2 / 3).coerceAtLeast(1L),
                            rate = if (slow) slowTailBytesPerSec else perConnectionBytesPerSec,
                            gracefulEnd = true
                        )
                        out.flush()
                        return false
                    }
                    streamBody(
                        out, payload, from, length,
                        cutAfter = if (cut) length / 3 else Long.MAX_VALUE,
                        rate = if (slow) slowTailBytesPerSec else perConnectionBytesPerSec
                    )
                }
            }
        }
        out.flush()
        return keepAlive
    }

    /**
     * Whole-body 200 path. When [gzipNonRanged] is on, the payload is gzipped *before* the header is
     * written so `Content-Length` matches the bytes on the wire and `Content-Encoding: gzip` is
     * advertised - a real HTTP server would never send an unencoded body marked as gzip.
     */
    private fun serveWholeBody(out: OutputStream, connection: String, liveValidator: String?) {
        if (gzipNonRanged) {
            val gz = ByteArrayOutputStream()
            GZIPOutputStream(gz).use { it.write(payload) }
            val bytes = gz.toByteArray()
            out.write(
                buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Content-Encoding: gzip\r\n")
                    if (acceptRangesHeader != null) append("Accept-Ranges: ").append(acceptRangesHeader).append("\r\n")
                    if (liveValidator != null) append("ETag: ").append(liveValidator).append("\r\n")
                    if (contentDisposition != null) append("Content-Disposition: ").append(contentDisposition).append("\r\n")
                    append("Connection: ").append(connection).append("\r\n\r\n")
                }.toByteArray(StandardCharsets.ISO_8859_1)
            )
            out.write(bytes)
            bytesServed.addAndGet(bytes.size.toLong())
            out.flush()
            return
        }
        writeHead(out, "HTTP/1.1 200 OK", payload.size.toLong(), liveValidator, connection)
        streamBody(out, payload, 0, payload.size.toLong())
    }

    private fun currentValidator(): String? =
        if (etagSwapAfterRequests >= 0 && requestCount.get() > etagSwapAfterRequests) etagAfterSwap else validator

    private fun isDataPlaneRequest(method: String, range: String?): Boolean {
        if (method == "HEAD") return false
        if (range == null) return true
        val (from, to) = parseRange(range, payload.size)
        return from != null && (to - from + 1) > 1
    }

    private fun writeFailure(out: OutputStream, status: Int, retryAfter: String?, connection: String) {
        val reason = when (status) {
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
            else -> "Unavailable"
        }
        out.write(
            buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                if (retryAfter != null) append("Retry-After: ").append(retryAfter).append("\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: ").append(connection).append("\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
        )
        out.flush()
    }

    private fun write206(
        out: OutputStream,
        from: Long,
        to: Long,
        length: Long,
        liveValidator: String?,
        connection: String,
    ) {
        out.write(
            buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                append("Content-Range: bytes ").append(from).append('-').append(to)
                    .append('/').append(payload.size).append("\r\n")
                if (acceptRangesHeader != null) append("Accept-Ranges: ").append(acceptRangesHeader).append("\r\n")
                if (liveValidator != null) append("ETag: ").append(liveValidator).append("\r\n")
                if (contentDisposition != null) append("Content-Disposition: ").append(contentDisposition).append("\r\n")
                append("Connection: ").append(connection).append("\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
        )
        out.flush()
    }

    private fun serveMultipart(out: OutputStream, range: String, connection: String, liveValidator: String?) {
        val boundary = "blaze-boundary-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val specs = range.removePrefix("bytes=").split(',').map { it.trim() }
        val parts = specs.mapNotNull { spec ->
            val dash = spec.indexOf('-')
            if (dash < 0) return@mapNotNull null
            val from = spec.substring(0, dash).toLongOrNull() ?: return@mapNotNull null
            val to = spec.substring(dash + 1).toLongOrNull() ?: (payload.size - 1).toLong()
            if (from >= payload.size) return@mapNotNull null
            from to minOf(to, (payload.size - 1).toLong())
        }
        if (parts.isEmpty()) {
            writeHead(out, "HTTP/1.1 416 Requested Range Not Satisfiable", -1, liveValidator, connection)
            return
        }
        val body = ByteArrayOutputStream()
        for ((from, to) in parts) {
            val length = to - from + 1
            body.write(("--$boundary\r\n").toByteArray(StandardCharsets.ISO_8859_1))
            body.write(("Content-Type: application/octet-stream\r\n").toByteArray(StandardCharsets.ISO_8859_1))
            body.write(
                ("Content-Range: bytes $from-$to/${payload.size}\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)
            )
            body.write(payload, from.toInt(), length.toInt())
            body.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        body.write(("--$boundary--\r\n").toByteArray(StandardCharsets.ISO_8859_1))
        val bytes = body.toByteArray()
        out.write(
            buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: multipart/byteranges; boundary=").append(boundary).append("\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                if (acceptRangesHeader != null) append("Accept-Ranges: ").append(acceptRangesHeader).append("\r\n")
                if (liveValidator != null) append("ETag: ").append(liveValidator).append("\r\n")
                append("Connection: ").append(connection).append("\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
        )
        out.write(bytes)
        bytesServed.addAndGet(bytes.size.toLong())
        out.flush()
    }

    private fun writeHead(
        out: OutputStream,
        status: String,
        length: Long,
        liveValidator: String?,
        connection: String = "close",
    ) {
        val header = buildString {
            append(status).append("\r\n")
            if (length >= 0) {
                append("Content-Length: ").append(length).append("\r\n")
                if (acceptRangesHeader != null) {
                    append("Accept-Ranges: ").append(acceptRangesHeader).append("\r\n")
                }
            }
            if (liveValidator != null) append("ETag: ").append(liveValidator).append("\r\n")
            if (contentDisposition != null) append("Content-Disposition: ").append(contentDisposition).append("\r\n")
            append("Connection: ").append(connection).append("\r\n\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
    }

    /**
     * Streams [length] bytes of [data] starting at [from], honouring the per-connection rate cap and
     * the optional mid-body [cutAfter] budget. When [gzip] is true the whole slice is gzipped first
     * and pushed as a single buffer with `Content-Encoding: gzip` (used only on non-ranged bodies).
     * When [gracefulEnd] is true the stream simply returns after writing fewer bytes than advertised,
     * closing the socket normally; when false a mid-body cut throws to simulate a connection drop.
     */
    private fun streamBody(
        out: OutputStream,
        data: ByteArray,
        from: Long,
        length: Long,
        cutAfter: Long = Long.MAX_VALUE,
        rate: Long = perConnectionBytesPerSec,
        gracefulEnd: Boolean = false,
    ) {
        val chunk = 8 * 1024
        val deadline = if (rate > 0) System.nanoTime() else 0L
        var sent = 0L
        var index = from
        while (sent < length) {
            val size = minOf(chunk.toLong(), length - sent, data.size - index).toInt()
            if (size <= 0) break
            if (bytesServed.get() >= earlyCloseAfterBytes || sent >= cutAfter) {
                if (gracefulEnd) return
                // Simulate a server that dies with the body half-finished. The budget is global, so
                // a segmented transfer completes some chunks and abandons others part-way.
                throw IOException("connection dropped")
            }
            out.write(data, index.toInt(), size)
            index += size
            sent += size
            bytesServed.addAndGet(size.toLong())
            if (rate > 0) {
                val allowedNanos = sent * 1_000_000_000L / rate
                val wait = deadline + allowedNanos - System.nanoTime()
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
    }

    private fun String.isMultiRange(): Boolean = removePrefix("bytes=").contains(',')

    private fun parseRange(range: String, size: Int): Pair<Long?, Long> {
        val spec = range.removePrefix("bytes=").trim()
        if (spec.contains(',')) return null to 0L
        val dash = spec.indexOf('-')
        if (dash < 0) return null to 0L
        val from = spec.substring(0, dash).toLongOrNull() ?: return null to 0L
        val to = spec.substring(dash + 1).toLongOrNull() ?: (size - 1).toLong()
        if (from >= size) return null to 0L
        return from to minOf(to, (size - 1).toLong())
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    companion object {
        fun of(size: Int, seed: Int = 1234_56789): ByteArray {
            val data = ByteArray(size)
            var state = seed
            for (i in data.indices) {
                state = state * 1103515245 + 12345
                data[i] = (state ushr 16).toByte()
            }
            return data
        }
    }
}
