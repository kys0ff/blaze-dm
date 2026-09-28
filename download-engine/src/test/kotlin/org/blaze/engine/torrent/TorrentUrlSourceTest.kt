package org.blaze.engine.torrent

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.execution.DownloadExecutorImpl
import org.blaze.engine.persistence.DownloadRecord
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.retry.DefaultRetryPolicy
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.storage.DefaultFileStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the `TorrentSource.Url` capability end to end inside the engine: a malformed metainfo URL
 * must fail cheaply (before any DHT/socket work), and the URL must survive the persistence round
 * trip so a restarted app re-creates the same torrent rather than a corrupt record.
 */
class TorrentUrlSourceTest {

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

    private fun createExecutor(task: DownloadTask, dir: Path): DownloadExecutorImpl = DownloadExecutorImpl(
        initialTask = task,
        httpClient = HttpClient(),
        storage = DefaultFileStorage(),
        settingsRepository = EngineSettingsRepository(dir),
        retryPolicy = DefaultRetryPolicy(DownloadSettings()),
        onMetadataResolved = { _, _ -> },
    )

    @Test
    fun `a non-parseable metainfo URL fails with InvalidTorrent without touching the network`() {
        for (bad in listOf("not a url at all", "ftp://example.com/x.torrent")) {
            val dir = tempDir("blaze-torrent-url-invalid")
            try {
                val request = DownloadRequest.Torrent("t", TorrentSource.Url(bad), dir.resolve("t"))
                val task = DownloadTask(DownloadId.generate(), "t", request, DownloadState.Queued, null, 0, 0)
                val tasks = mutableListOf<DownloadTask>()
                runBlocking {
                    createExecutor(task, dir).execute().collect { tasks.add(it) }
                }
                assertTrue(
                    tasks.any { it.error == DownloadError.InvalidTorrent },
                    "'$bad' should be rejected as an invalid torrent, saw: ${tasks.map { it.error }}",
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a metainfo URL survives the persistence round trip`() {
        val dir = tempDir("blaze-torrent-url-persist")
        try {
            val repository = DownloadRepository(dir)
            val url = "https://example.com/files/Big.Buck.torrent"
            repository.saveAll(
                listOf(
                    DownloadRecord(
                        id = "u-1",
                        name = "Big.Buck",
                        type = "TORRENT",
                        torrentUrl = url,
                        destination = "/tmp/big",
                        state = "COMPLETED",
                        addedAt = System.currentTimeMillis(),
                    ),
                ),
            )
            val loaded = repository.loadAll()
            assertEquals(1, loaded.size)
            assertEquals(url, loaded.single().torrentUrl)
            assertNull(loaded.single().magnetUri, "a URL record must not grow a magnet field")
            assertNull(loaded.single().torrentPath, "a URL record must not grow a path field")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `records written before torrent URLs existed still load`() {
        val dir = tempDir("blaze-torrent-url-legacy")
        try {
            Files.writeString(
                dir.resolve("downloads.json"),
                """[{"id":"old-1","name":"m","type":"TORRENT","magnetUri":"magnet:?xt=urn:btih:abc",""" +
                    """"destination":"/tmp/m","state":"COMPLETED","addedAt":1}]""",
            )
            val loaded = DownloadRepository(dir).loadAll()
            assertEquals(1, loaded.size, "the additive torrentUrl field must not break old files")
            assertNull(loaded.single().torrentUrl)
            assertEquals("magnet:?xt=urn:btih:abc", loaded.single().magnetUri)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a restarted manager reads a URL record back as a URL torrent source`() {
        val dir = tempDir("blaze-torrent-url-restore")
        val storageDir = dir.resolve("storage")
        Files.createDirectories(storageDir)
        val url = "https://example.com/f/Linux.torrent"
        DownloadRepository(storageDir).saveAll(
            listOf(
                DownloadRecord(
                    id = "r-1",
                    name = "Linux",
                    type = "TORRENT",
                    torrentUrl = url,
                    destination = "/tmp/linux",
                    state = "COMPLETED", // a restored download never restarts itself
                    addedAt = System.currentTimeMillis(),
                ),
            ),
        )
        val managerScope = CoroutineScope(Job() + Dispatchers.IO)
        val manager = DownloadManager(
            scope = managerScope,
            repository = DownloadRepository(storageDir),
            settingsRepository = EngineSettingsRepository(storageDir),
        )
        try {
            var task: DownloadTask? = null
            for (i in 1..500) {
                task = runBlocking { manager.getTask(DownloadId("r-1")) }
                if (task != null) break
                Thread.sleep(10)
            }
            val request = requireTorrent(task, url)
            assertEquals(TorrentSource.Url(url), request.torrentSource)
        } finally {
            runBlocking { manager.shutdown() }
            managerScope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    private fun requireTorrent(task: DownloadTask?, url: String): DownloadRequest.Torrent {
        assertTrue(task != null, "record should be restored, waited for URL source $url")
        val request = task!!.request
        assertTrue(request is DownloadRequest.Torrent, "restored request should be a torrent")
        return request
    }
}
