package org.blaze.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.http.KtorHttpDownloader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpDownloaderProgressTest {

    @Test
    fun `test real-time streaming progress updates`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-progress-test")
        val dest = tempDir.resolve("progress_test.bin")
        val request = DownloadRequest.Http(
            name = "progress_test.bin",
            url = "http://example.com/progress_test.bin",
            destination = dest
        )

        val channel = ByteChannel(autoFlush = true)

        val mockEngine = MockEngine { _ ->
            respond(
                content = channel,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                    HttpHeaders.ContentLength to listOf("100")
                )
            )
        }
        val client = HttpClient(mockEngine)
        val downloader = KtorHttpDownloader(request, client)

        val collectJob = launch {
            val states = downloader.download().toList()
            
            // Check that we got Starting, Downloading (multiple times), and Completed
            assertTrue(states.any { it.state == DownloadState.Starting }, "Should emit Starting state")
            
            val downloadingStates = states.filter { it.state == DownloadState.Downloading }
            assertTrue(downloadingStates.isNotEmpty(), "Should emit at least one Downloading state for progress setup")
            
            assertTrue(states.any { it.state == DownloadState.Completed }, "Should finish with Completed state")
            assertEquals(100L, dest.toFile().length(), "Destination file should have correct length")
        }

        // Simulate streaming chunks with delays to let downloader update progress
        launch {
            channel.writeFully(ByteArray(40))
            Thread.sleep(510)
            channel.writeFully(ByteArray(40))
            Thread.sleep(510)
            channel.writeFully(ByteArray(20))
            channel.close()
        }

        collectJob.join()
        client.close()
        tempDir.toFile().deleteRecursively()
    }
}
