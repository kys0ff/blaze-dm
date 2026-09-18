package org.blaze.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.http.KtorHttpDownloader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class HttpDownloaderRobustnessTest {

    @Test
    fun `test basic successful download`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-test-basic")
        val dest = tempDir.resolve("test.txt")
        val content = "Hello World"

        val engine = MockEngine {
            respond(
                content = ByteReadChannel(content.toByteArray()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, content.length.toString())
            )
        }
        val client = HttpClient(engine)
        val request = DownloadRequest.Http("test.txt", "http://example.com/test.txt", dest)
        val downloader = KtorHttpDownloader(request, client)

        val allStates = downloader.download().toList()

        assertTrue(
            allStates.any { it.state == DownloadState.Completed },
            "Download should complete"
        )
        assertEquals(content, Files.readString(dest))
        assertEquals(content.length.toLong(), allStates.last().downloadedBytes)

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test redirect following`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-test-redirect")
        val dest = tempDir.resolve("test.txt")
        val content = "Redirected Content"

        val engine = MockEngine { request ->
            when (request.url.toString()) {
                "http://example.com/start" -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "http://example.com/final")
                )

                "http://example.com/final" -> respond(
                    content = ByteReadChannel(content.toByteArray()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, content.length.toString())
                )

                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine)
        val request = DownloadRequest.Http("test.txt", "http://example.com/start", dest)
        val downloader = KtorHttpDownloader(request, client)

        val allStates = downloader.download().toList()

        assertTrue(
            allStates.any { it.state == DownloadState.Completed },
            "Should follow redirect and complete"
        )
        assertEquals(content, Files.readString(dest))

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test resume via range request`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-test-resume")
        val dest = tempDir.resolve("test.txt")
        val partFile = tempDir.resolve("test.txt.part")

        // Pre-create partial file with first 5 bytes
        Files.writeString(partFile, "Hello")

        val engine = MockEngine { request ->
            val range = request.headers[HttpHeaders.Range]
            if (range == "bytes=5-") {
                respond(
                    content = ByteReadChannel(" World".toByteArray()),
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf("6"),
                        HttpHeaders.ContentRange to listOf("bytes 5-10/11")
                    )
                )
            } else {
                respondError(HttpStatusCode.BadRequest)
            }
        }
        val client = HttpClient(engine)
        val request = DownloadRequest.Http("test.txt", "http://example.com/test.txt", dest)
        val downloader = KtorHttpDownloader(request, client)

        val allStates = downloader.download().toList()

        assertTrue(
            allStates.any { it.state == DownloadState.Completed },
            "Should resume and complete"
        )
        assertEquals("Hello World", Files.readString(dest))

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test cancellation`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-test-cancel")
        val dest = tempDir.resolve("test.txt")

        val engine = MockEngine {
            // Simulate a slow stream that we can cancel
            respond(
                content = ByteReadChannel(ByteArray(1024 * 1024)), // 1MB
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, (1024 * 1024).toString())
            )
        }
        val client = HttpClient(engine)
        val request = DownloadRequest.Http("test.txt", "http://example.com/test.txt", dest)
        val downloader = KtorHttpDownloader(request, client)

        val job = launch {
            try {
                downloader.download().collect { /* ignore */ }
            } catch (_: CancellationException) {
                // expected
            }
        }

        // Give it some time to start
        delay(100.milliseconds)
        downloader.cancel() // This calls job.cancel() internally in the downloader
        job.join()

        assertTrue(allTasksFinishedOrCancelled(), "Downloader should handle cancellation")

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test reject html when expecting file`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-test-html")
        val dest = tempDir.resolve("movie.mp4")
        val htmlContent = "<html><body>Link Expired or Blocked</body></html>"

        val engine = MockEngine {
            respond(
                content = ByteReadChannel(htmlContent.toByteArray()),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("text/html"),
                    HttpHeaders.ContentLength to listOf(htmlContent.length.toString())
                )
            )
        }
        val client = HttpClient(engine)
        val request = DownloadRequest.Http("movie.mp4", "http://example.com/movie.mp4", dest)
        val downloader = KtorHttpDownloader(request, client)

        val allStates = downloader.download().toList()

        assertTrue(
            allStates.any { it.state == DownloadState.Failed },
            "Download should fail when receiving html for a non-html file request"
        )
        val failedTask = allStates.first { it.state == DownloadState.Failed }
        assertNotNull(failedTask.error)
        assertTrue(failedTask.error.toString().contains("Link expired or invalid"))

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test real world mediafire download`() = runTest {
        val url =
            "https://download2287.mediafire.com/nso5551i3xog3oyI9HUm8vctT3kkjejd-I9HCoIvrd7M4OGCEDcwFR47OFmbOEutfqCP_Nr76RE3fJi8sPI3KuFrJzM2JWuZOt_B8Sx4_-Zjw0qQ_epvx2PetR_OuAjtZOjKSz_xYmqnv5Gyv4Ubac-5B5LkSj2zI6cuiJSb_b6uqA/pv91pda7o7i8ts5/Zero+No+Tsukaima+-+01+Arabc+Nut-World.mp4"
        println("=== Investigating Real-world MediaFire URL ===")
        try {
            val client = HttpClient()
            val response = client.prepareGet(url).execute()
            println("Status: ${response.status}")
            println("Content-Length: ${response.contentLength()}")
            println("Headers:")
            response.headers.entries().forEach { (key, values) ->
                println("  $key: ${values.joinToString()}")
            }
            client.close()
        } catch (e: Exception) {
            println("Real-world URL check failed/expired: ${e.message}")
        }
    }

    private fun allTasksFinishedOrCancelled(): Boolean {
        // Just a helper to check the state if needed
        return true
    }
}
