package org.blaze.engine

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.http.KtorHttpDownloader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class HttpDownloaderTest {

    @Test
    fun `test download lifecycle`() = runTest {
        val tempDir = Files.createTempDirectory("blaze-test")
        val dest = tempDir.resolve("test.txt")
        // Use a small reliable file for testing
        val request = DownloadRequest.Http(
            name = "test.txt",
            url = "https://raw.githubusercontent.com/JetBrains/kotlin/main/README.md",
            destination = dest
        )
        
        val downloader = KtorHttpDownloader(request)
        val states = downloader.download().take(5).toList()
        
        assertTrue(states.any { it.state == DownloadState.Starting || it.state == DownloadState.Downloading })
        
        // Clean up
        tempDir.toFile().deleteRecursively()
    }
}
