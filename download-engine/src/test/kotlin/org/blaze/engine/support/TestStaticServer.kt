package org.blaze.engine.support

import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A path-routing loopback HTTP server for tests that need *several* resources from one host — an HLS
 * master playlist, its media playlists, segments and keys; or a ZIP whose byte ranges must be served
 * selectively. [TestHttpServer] answers every request with one fixed body, which cannot express those
 * shapes, so this companion maps each requested path to its own bytes while keeping real HTTP semantics
 * (HEAD, `Range`, `Content-Range`, `Accept-Ranges`, early EOF).
 *
 * Like its sibling it exists to make assertions about on-the-wire behaviour rather than a friendly mock:
 * [pathRequests] records the paths actually asked for, and [bytesServed] totals the body bytes pushed,
 * which is what proves a selective read never fetched the whole archive.
 */
class TestStaticServer(
    private val resources: Map<String, ByteArray>,
    private val supportsRanges: Boolean = true,
    private val acceptRangesHeader: String? = "bytes",
) : AutoCloseable {
    private val server = ServerSocket(0)
    private val running = AtomicBoolean(true)
    private val accepting = Thread { acceptLoop() }.apply { isDaemon = true; name = "test-static-accept" }

    /** Paths requested (query stripped), in order — used to prove which resources were touched. */
    val pathRequests: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
    val bytesServed = AtomicLong(0)
    val rangeRequestCount = AtomicLong(0)

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        accepting.start()
    }

    fun url(path: String): String = baseUrl + path

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            Thread { handle(socket) }.apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                val reader = BufferedReader(it.getInputStream().reader(StandardCharsets.ISO_8859_1))
                val out = it.getOutputStream()
                val requestLine = reader.readLine() ?: return
                val method = requestLine.substringBefore(' ').uppercase()
                val target = requestLine.split(' ').getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                serve(out, method, path, range)
            } catch (_: Exception) {
                // Client hung up; the scenarios here never rely on keep-alive.
            }
        }
    }

    private fun serve(out: OutputStream, method: String, path: String, range: String?) {
        pathRequests.add(path)
        val body = resources[path]
        if (body == null) {
            writeHead(out, "HTTP/1.1 404 Not Found", -1, null)
            out.flush()
            return
        }
        if (method == "HEAD") {
            writeHead(out, "HTTP/1.1 200 OK", body.size.toLong(), acceptRangesHeader)
            out.flush()
            return
        }
        if (range == null || !supportsRanges) {
            writeHead(out, "HTTP/1.1 200 OK", body.size.toLong(), acceptRangesHeader)
            out.write(body)
            bytesServed.addAndGet(body.size.toLong())
            out.flush()
            return
        }
        rangeRequestCount.incrementAndGet()
        val (from, to) = parseRange(range, body.size)
        if (from == null) {
            writeHead(out, "HTTP/1.1 416 Requested Range Not Satisfiable", -1, acceptRangesHeader)
            out.flush()
            return
        }
        val length = to - from + 1
        out.write(
            buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(body.size)
                    .append("\r\n")
                if (acceptRangesHeader != null) append("Accept-Ranges: ").append(acceptRangesHeader).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
        )
        out.write(body, from.toInt(), length.toInt())
        bytesServed.addAndGet(length)
        out.flush()
    }

    private fun writeHead(out: OutputStream, status: String, length: Long, acceptRanges: String?) {
        val header = buildString {
            append(status).append("\r\n")
            if (length >= 0) {
                append("Content-Length: ").append(length).append("\r\n")
                if (acceptRanges != null) append("Accept-Ranges: ").append(acceptRanges).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
    }

    private fun parseRange(range: String, size: Int): Pair<Long?, Long> {
        val spec = range.removePrefix("bytes=").trim()
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
}
