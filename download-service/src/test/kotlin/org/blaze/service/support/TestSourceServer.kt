package org.blaze.service.support

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * A tiny loopback file server for real-download integration tests.
 *
 * It serves a fixed byte payload at [path] (and honours `Range` well enough for a segmented client to
 * finish), so the *actual* engine + HTTP client run end-to-end through the application API - exactly what
 * the task requires: verify `HTTP API -> Application API -> engine` really moves bytes, not just mocks.
 */
class TestSourceServer(
    payload: ByteArray,
    private val requireAuthorization: String? = null,
    private val contentType: String = "application/octet-stream",
    private val dispositionFilename: String? = null,
) : AutoCloseable {

    private val bytes = payload
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool { r -> Thread(r, "test-source").apply { isDaemon = true } }
        createContext("/") { exchange ->
            val auth = requireAuthorization
            if (auth != null) {
                val presented = exchange.requestHeaders.getFirst("Authorization")
                if (presented != auth) {
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                    return@createContext
                }
            }
            val range = exchange.requestHeaders.getFirst("Range")
            val start = range?.substringAfter('=')?.substringBefore('-')?.toLongOrNull() ?: 0L
            val endRequested = range?.substringAfter('-')?.toLongOrNull()
            val from = start.coerceIn(0L, bytes.size.toLong())
            val to = (endRequested ?: (bytes.size - 1L)).coerceIn(from, (bytes.size - 1).toLong())
            val slice = bytes.copyOfRange(from.toInt(), (to + 1).toInt())

            if (dispositionFilename != null) {
                exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"$dispositionFilename\"")
            }
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Type", contentType)
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${bytes.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
            }
            exchange.responseBody.use { it.write(slice) }
        }
        start()
    }

    val port: Int get() = server.address.port
    fun url(path: String = "/file"): String = "http://127.0.0.1:$port$path"

    override fun close() = server.stop(0)
}

/** Convenience: UTF-8 payload of a given logical size built from a repeating line. */
fun payloadOf(size: Int): ByteArray {
    val template = "Blaze-download-payload-line-0123456789\n".toByteArray(StandardCharsets.UTF_8)
    return ByteArray(size) { template[it % template.size] }
}
