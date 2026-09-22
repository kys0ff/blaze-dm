package org.blaze.engine.support

import java.io.BufferedReader
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

/**
 * Minimal loopback HTTP/1.1 server for exercising the transfer paths.
 *
 * It exists so the tests assert on real socket behaviour (status lines, `Range`, `Content-Range`,
 * early EOF) instead of a mocked client that would happily accept the bugs those tests target.
 * Every knob models a failure mode seen in the wild.
 */
class TestHttpServer(
    private val payload: ByteArray,
    private val supportsRanges: Boolean = true,
    /** Mutable so a test can fail the first attempt and let the resume succeed. */
    @Volatile var earlyCloseAfterBytes: Long = Long.MAX_VALUE,
    private val perConnectionBytesPerSec: Long = 0L,
    private val headAllowed: Boolean = true,
    private val validator: String? = null,
    /**
     * How many ranged bodies are cut short after a third of their length. Models the flaky host
     * that drops *some* connections while the rest of the transfer is fine - the download has to
     * recover by re-reading those chunks, not by failing.
     */
    private val cutFirstBodies: Int = 0
) : AutoCloseable {
    private val server = ServerSocket(0)
    private val running = AtomicBoolean(true)
    private val accepting = Thread { acceptLoop() }.apply { isDaemon = true; name = "test-http-accept" }

    /** Every `Range` header received, in order, including the empty string for rangeless GETs. */
    val rangeRequests: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /** Total body bytes pushed at clients, the cheapest way to prove a resume did not re-download. */
    val bytesServed = AtomicLong(0)
    val requestCount = AtomicLong(0)
    private val bodiesServed = AtomicInteger(0)

    val port: Int get() = server.localPort
    val url: String get() = "http://127.0.0.1:$port/file.bin"

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
            Thread { handle(socket) }.apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                val input: InputStream = it.getInputStream()
                val reader = BufferedReader(input.reader(StandardCharsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                val method = requestLine.substringBefore(' ').uppercase()
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                requestCount.incrementAndGet()
                rangeRequests.add(range ?: "")

                val out = it.getOutputStream()
                when {
                    method == "HEAD" && !headAllowed -> {
                        writeHead(out, "HTTP/1.1 405 Method Not Allowed", -1, null)
                    }

                    method == "HEAD" -> {
                        writeHead(out, "HTTP/1.1 200 OK", payload.size.toLong(), validator)
                    }

                    // The dangerous server: it answers a range request with the whole body, which
                    // is exactly the case that used to shift every byte and corrupt the file.
                    range == null || !supportsRanges -> {
                        writeHead(out, "HTTP/1.1 200 OK", payload.size.toLong(), validator)
                        stream(out, payload, 0, payload.size.toLong())
                    }

                    else -> {
                        val (from, to) = parseRange(range, payload.size)
                        if (from == null) {
                            writeHead(out, "HTTP/1.1 416 Requested Range Not Satisfiable", -1, null)
                        } else {
                            val length = to - from + 1
                            out.write(
                                buildString {
                                    append("HTTP/1.1 206 Partial Content\r\n")
                                    append("Content-Length: ").append(length).append("\r\n")
                                    append("Content-Range: bytes ").append(from).append('-').append(to)
                                        append('/').append(payload.size).append("\r\n")
                                    append("Accept-Ranges: bytes\r\n")
                                    if (validator != null) append("ETag: ").append(validator).append("\r\n")
                                    append("Connection: close\r\n\r\n")
                                }.toByteArray(StandardCharsets.ISO_8859_1)
                            )
                            out.flush()
                            val cut = bodiesServed.getAndIncrement() < cutFirstBodies
                            stream(out, payload, from, length, cutAfter = if (cut) length / 3 else Long.MAX_VALUE)
                        }
                    }
                }
                out.flush()
            } catch (_: Exception) {
                // The client hung up mid-transfer; that is itself a scenario worth testing.
            }
        }
    }

    private fun writeHead(out: OutputStream, status: String, length: Long, validator: String?) {
        val header = buildString {
            append(status).append("\r\n")
            if (length >= 0) {
                append("Content-Length: ").append(length).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
            }
            if (validator != null) append("ETag: ").append(validator).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
    }

    private fun stream(
        out: OutputStream,
        data: ByteArray,
        from: Long,
        length: Long,
        cutAfter: Long = Long.MAX_VALUE
    ) {
        val chunk = 8 * 1024
        val deadline = if (perConnectionBytesPerSec > 0) System.nanoTime() else 0L
        var sent = 0L
        var index = from
        while (sent < length) {
            val size = minOf(chunk.toLong(), length - sent, data.size - index).toInt()
            if (size <= 0) break
            if (bytesServed.get() >= earlyCloseAfterBytes || sent >= cutAfter) {
                // Simulate a server that dies with the body half-finished. The budget is global, so
                // a segmented transfer completes some chunks and abandons others part-way.
                throw IOException("connection dropped")
            }
            out.write(data, index.toInt(), size)
            index += size
            sent += size
            bytesServed.addAndGet(size.toLong())
            if (perConnectionBytesPerSec > 0) {
                val allowedNanos = sent * 1_000_000_000L / perConnectionBytesPerSec
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
