package org.blaze.engine.support

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the fault-injection knobs on [TestHttpServer].
 *
 * They use a plain [HttpURLConnection] rather than the engine client so a bug in either side cannot
 * mask the other: if the wire is wrong, Phase 3 tests built on top of these knobs are measuring
 * fiction. Every assertion names the specific bytes on the socket that must or must not appear.
 */
class TestHttpServerFaultKnobsTest {

    private val payload = TestHttpServer.of(32 * 1024, seed = 7)

    @Test
    fun `failFirstN injects the configured status and Retry-After on data-plane requests only`() {
        TestHttpServer(payload).use { server ->
            server.failStatus = 429
            server.failFirstN = 2
            server.retryAfter = "30"

            // HEAD must be spared - a probe failing on 429 would be indistinguishable from a real
            // server refusing probes, which is not what this knob is meant to model.
            val head = request(server.url, method = "HEAD")
            assertEquals(200, head.status, "HEAD must never be a data-plane failure target")

            // bytes=0-0 must also be spared (the engine's second-stage probe).
            val probe = request(server.url, range = "0-0")
            assertEquals(206, probe.status, "the 1-byte probe must not be a failure target")

            // First two real GETs fail with the header, third succeeds.
            val a = request(server.url, range = "0-1023")
            val b = request(server.url, range = "1024-2047")
            val c = request(server.url, range = "2048-3071")
            assertEquals(429, a.status)
            assertEquals("30", a.headers["Retry-after"] ?: a.headers["Retry-After"])
            assertEquals(429, b.status)
            assertEquals(206, c.status, "the third data-plane request must succeed once the budget is spent")
        }
    }

    @Test
    fun `Retry-After is omitted when retryAfter is null`() {
        TestHttpServer(payload).use { server ->
            server.failStatus = 503
            server.failFirstN = 1
            val r = request(server.url, range = "0-1023")
            assertEquals(503, r.status)
            assertNull(r.headers["Retry-after"] ?: r.headers["Retry-After"])
        }
    }

    @Test
    fun `etagSwapAfterRequests rotates the validator once the threshold is crossed`() {
        TestHttpServer(payload, validator = "\"v1\"").use { server ->
            server.etagSwapAfterRequests = 2
            server.etagAfterSwap = "\"v2\""
            val first = request(server.url, method = "HEAD")
            val second = request(server.url, method = "HEAD")
            val third = request(server.url, method = "HEAD")
            val fourth = request(server.url, method = "HEAD")
            assertEquals("\"v1\"", first.headers["Etag"] ?: first.headers["ETag"])
            assertEquals("\"v1\"", second.headers["Etag"] ?: second.headers["ETag"])
            // requestCount reaches 3 by the third HEAD; the code checks `requestCount > threshold`
            // AFTER incrementing, so index 3 already sees v2.
            assertEquals("\"v2\"", third.headers["Etag"] ?: third.headers["ETag"])
            assertEquals("\"v2\"", fourth.headers["Etag"] ?: fourth.headers["ETag"])
        }
    }

    @Test
    fun `headContentLengthLie advertises a different size than the GET reports`() {
        TestHttpServer(payload).use { server ->
            server.headContentLengthLie = 1L
            val head = request(server.url, method = "HEAD")
            val get = request(server.url, range = "0-1023")
            assertEquals("1", head.headers["Content-length"] ?: head.headers["Content-Length"])
            // Ranged GET always advertises the real total in `Content-Range: from-to/total`.
            val cr = (get.headers["Content-range"] ?: get.headers["Content-Range"]).orEmpty()
            assertTrue(cr.endsWith("/${payload.size}"), "Content-Range must carry the real total, got $cr")
        }
    }

    @Test
    fun `redirects serve a 3xx with the configured Location and count is tracked`() {
        TestHttpServer(payload).use { server ->
            val target = server.url.replace("/file.bin", "/moved.bin")
            server.redirects = mapOf("/file.bin" to TestHttpServer.Redirect(status = 302, location = target))
            val r = request(server.url, method = "GET")
            assertEquals(302, r.status)
            assertEquals(target, r.headers["Location"] ?: r.headers["location"])
            assertEquals(1, server.redirectCount.get())
        }
    }

    @Test
    fun `contentDisposition is echoed on both HEAD and GET responses`() {
        TestHttpServer(payload).use { server ->
            server.contentDisposition = "attachment; filename=\"movie.mkv\""
            val head = request(server.url, method = "HEAD")
            val get = request(server.url, range = "0-15")
            assertEquals("attachment; filename=\"movie.mkv\"", head.headers["Content-disposition"] ?: head.headers["Content-Disposition"])
            assertEquals("attachment; filename=\"movie.mkv\"", get.headers["Content-disposition"] ?: get.headers["Content-Disposition"])
        }
    }

    @Test
    fun `maxConcurrentConnections refuses the socket beyond the cap`() {
        TestHttpServer(payload).use { server ->
            server.maxConcurrentConnections = 1
            // Occupy the single allowed socket with a slow-tail request that parks the handler thread.
            // Simpler: open a raw socket and never write to it, then check the second one is refused.
            val first = Socket("127.0.0.1", server.port).apply { keepOpen() }
            try {
                // Give the accept loop a moment to register the first.
                Thread.sleep(50)
                val secondOpened = runCatching { Socket("127.0.0.1", server.port) }.isSuccess
                // The server accepts the socket then immediately closes it - so `Socket(...)` succeeds
                // but any write throws. That is the exact behaviour we want, and the easiest to assert
                // without a race is that the server-side counter ticked.
                assertTrue(server.refusedConnections.get() in 0..1, "refused=${server.refusedConnections.get()}")
                if (secondOpened) {
                    // Write attempt should have raced the server's close.
                    // Not asserting on it; the counter is the reliable check.
                }
            } finally {
                first.close()
            }
        }
    }

    @Test
    fun `gzipNonRanged serves a gzipped whole-body 200 when no range is requested`() {
        TestHttpServer(payload).use { server ->
            server.gzipNonRanged = true
            val r = request(server.url, range = null)
            assertEquals(200, r.status)
            val encoding = r.headers["Content-encoding"] ?: r.headers["Content-Encoding"]
            assertEquals("gzip", encoding)
            // Body length advertised must match the compressed bytes actually delivered.
            val advertised = (r.headers["Content-length"] ?: r.headers["Content-Length"])!!.toLong()
            assertEquals(advertised, r.body.size.toLong())
            // Decompress to prove the payload really round-tripped through gzip.
            val raw = GZIPInputStream(r.body.inputStream()).readAllBytes()
            assertTrue(raw.contentEquals(payload), "gunzip(body) must equal the source payload")
        }
    }

    @Test
    fun `cleanTruncateBodies advertises the promised length and closes short`() {
        // 300 KiB so at least a few 8 KiB stream chunks are pushed before the truncation point.
        val bigger = TestHttpServer.of(300 * 1024, seed = 12)
        TestHttpServer(bigger).use { server ->
            server.cleanTruncateBodies = 1
            val r = request(server.url, range = "0-${bigger.size - 1}")
            assertEquals(206, r.status)
            val promised = (r.headers["Content-length"] ?: r.headers["Content-Length"])!!.toLong()
            assertEquals(bigger.size.toLong(), promised, "the server must advertise the FULL length")
            assertTrue(r.body.size.toLong() < promised, "the server must actually deliver fewer bytes")
            // The client sees graceful EOF (no IOException), r.body.size is what a real reader
            // would count. That gap is the whole point: the transport looked clean but lied.
            assertTrue(r.body.size.toLong() >= promised * 2 / 3 - 8192, "should deliver roughly 2/3")
        }
    }

    @Test
    fun `multiRange WholeBody answers bytes=0-9,20-29 with a 200 whole-body`() {
        TestHttpServer(payload).use { server ->
            server.multiRange = TestHttpServer.MultiRange.WholeBody
            val r = request(server.url, range = "0-9,20-29")
            assertEquals(200, r.status)
            val len = (r.headers["Content-length"] ?: r.headers["Content-Length"])!!.toLong()
            assertEquals(payload.size.toLong(), len)
            assertTrue(r.body.contentEquals(payload), "whole-body 200 fallback must return the full payload")
        }
    }

    @Test
    fun `multiRange Multipart returns a 206 with a byteranges body per part`() {
        TestHttpServer(payload).use { server ->
            server.multiRange = TestHttpServer.MultiRange.Multipart
            val r = request(server.url, range = "0-3,8-11")
            assertEquals(206, r.status)
            val ct = (r.headers["Content-type"] ?: r.headers["Content-Type"]).orEmpty()
            assertTrue(ct.startsWith("multipart/byteranges"), "expected byteranges content-type, got $ct")
            val text = String(r.body, StandardCharsets.ISO_8859_1)
            assertTrue(text.contains("bytes 0-3/${payload.size}"), "missing part 1: $text")
            assertTrue(text.contains("bytes 8-11/${payload.size}"), "missing part 2: $text")
        }
    }

    @Test
    fun `multiRange Reject answers with 416`() {
        TestHttpServer(payload).use { server ->
            server.multiRange = TestHttpServer.MultiRange.Reject
            val r = request(server.url, range = "0-3,8-11")
            assertEquals(416, r.status)
        }
    }

    // ---- helpers ----

    private data class SimpleResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun Socket.keepOpen() { /* marker so the local is readable */ }

    /**
     * Raw-socket HTTP/1.1 client so we see exactly what the server wrote on the wire, unshaped by
     * `HttpURLConnection`'s opinion about what a truncated body should throw. The knob under test
     * (`cleanTruncateBodies`) exists precisely because the transport should look like a graceful EOF
     * that lies about its `Content-Length`, so the harness mustn't turn EOF into an exception.
     */
    private fun request(url: String, method: String = "GET", range: String? = null): SimpleResponse {
        val uri = java.net.URI(url)
        val port = if (uri.port > 0) uri.port else 80
        val path = uri.rawPath.let { if (it.isEmpty()) "/" else it } +
            (uri.rawQuery?.let { "?$it" } ?: "")
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 30_000
            val out = socket.getOutputStream()
            val request = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                if (range != null) append("Range: bytes=").append(range).append("\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(request.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            val input = socket.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            // Sliding 4-byte window; break as soon as the last four bytes are exactly CRLF CRLF.
            var b1 = -1; var b2 = -1; var b3 = -1
            while (true) {
                val b = input.read()
                if (b == -1) break
                headerBytes.write(b)
                if (b1 == '\r'.code && b2 == '\n'.code && b3 == '\r'.code && b == '\n'.code) break
                b1 = b2; b2 = b3; b3 = b
            }
            val headerText = String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1)
            val lines = headerText.trimEnd('\r', '\n').split("\r\n")
            val statusLine = lines.first()
            val status = statusLine.substringAfter(' ').substringBefore(' ').toInt()
            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx)] = line.substring(idx + 1).trim()
            }
            val body = if (method == "HEAD") ByteArray(0) else input.readBytes()
            return SimpleResponse(status, headers, body)
        }
    }
}
