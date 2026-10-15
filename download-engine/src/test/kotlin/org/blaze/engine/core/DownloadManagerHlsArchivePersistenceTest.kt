package org.blaze.engine.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import kotlin.test.*

/**
 * Guards the persistence contract for the two new kinds: an HLS or remote-archived download must survive a
 * restart with its source, variant/selection and DRM choice intact, and must never carry its request
 * headers (auth tokens, cookies) onto disk — a resumed download re-prompts rather than reading a secret
 * back out of the record store.
 */
class DownloadManagerHlsArchivePersistenceTest {

    /** A do-nothing executor so enqueue/start never touch the network; persistence is what is under test. */
    private val noopFactory: (DownloadTask) -> DownloadExecutor = {
        object : DownloadExecutor {
            override fun execute() = emptyFlow<DownloadTask>()
        }
    }

    @Test
    fun `HLS and Archive requests round-trip through the store without their headers`(): Unit = runBlocking {
        val tempDir = Files.createTempDirectory("blaze-hls-archive-persist")
        val storageDir = tempDir.resolve("storage").also { Files.createDirectories(it) }

        val hlsId: String
        val zipId: String
        run {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val manager = DownloadManager(
                scope = scope,
                repository = DownloadRepository(storageDir),
                settingsRepository = EngineSettingsRepository(storageDir),
                executorFactory = noopFactory,
            )
            val hls = manager.enqueue(
                DownloadRequest.Hls(
                    name = "video.ts",
                    playlistUrl = "http://host/master.m3u8",
                    destination = tempDir.resolve("video.ts"),
                    headers = mapOf("Authorization" to "Bearer super-secret"),
                    variantIndex = 2,
                    drmAcknowledged = true,
                )
            )
            val zip = manager.enqueue(
                DownloadRequest.Archive(
                    name = "bundle.zip",
                    url = "http://host/bundle.zip",
                    destination = tempDir.resolve("out"),
                    headers = mapOf("Cookie" to "session=abc123"),
                    selectedEntries = listOf("docs/a.txt", "docs/b.txt"),
                )
            )
            hlsId = hls.value
            zipId = zip.value
            manager.shutdown() // flushes the snapshot before cancelling the scope
        }

        // Reload from disk in a fresh manager and reconstruct the requests from their records.
        val reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reload = DownloadManager(
            scope = reloadScope,
            repository = DownloadRepository(storageDir),
            settingsRepository = EngineSettingsRepository(storageDir),
            executorFactory = noopFactory,
        )
        try {
            val hlsTask = awaitTask(reload, hlsId)
            val hlsRequest = hlsTask.request
            assertIs<DownloadRequest.Hls>(hlsRequest)
            assertEquals("http://host/master.m3u8", hlsRequest.playlistUrl)
            assertEquals(2, hlsRequest.variantIndex)
            assertTrue(hlsRequest.drmAcknowledged, "the acknowledged DRM choice must survive a restart")
            assertTrue(hlsRequest.headers.isEmpty(), "credentials are never persisted")

            val zipTask = awaitTask(reload, zipId)
            val zipRequest = zipTask.request
            assertIs<DownloadRequest.Archive>(zipRequest)
            assertEquals("http://host/bundle.zip", zipRequest.url)
            assertEquals(listOf("docs/a.txt", "docs/b.txt"), zipRequest.selectedEntries)
            assertTrue(zipRequest.headers.isEmpty(), "credentials are never persisted")

            // The serialized record must not have leaked either header value to disk.
            val raw = Files.readString(storageDir.resolve("downloads.json"))
            assertTrue("super-secret" !in raw && "session=abc123" !in raw, "secrets must not be written to the store")
            assertContains(raw, "\"HLS\"")
            assertContains(raw, "\"ARCHIVE\"")
        } finally {
            reload.shutdown()
            tempDir.toFile().deleteRecursively()
        }
    }

    private suspend fun awaitTask(manager: DownloadManager, id: String): DownloadTask {
        val downloadId = org.blaze.engine.api.DownloadId(id)
        return withTimeout(5_000) {
            var found: DownloadTask? = null
            while (found == null) {
                found = manager.getTask(downloadId)
                if (found == null) delay(20)
            }
            requireNotNull(found)
        }
    }
}
