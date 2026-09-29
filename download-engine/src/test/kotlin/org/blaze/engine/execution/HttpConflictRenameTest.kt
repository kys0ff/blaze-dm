package org.blaze.engine.execution

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.CopyName
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.retry.DefaultRetryPolicy
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.support.TestHttpServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * HTTP is the half of the conflict policy that has to be decided mid-transfer: unlike a torrent, which
 * owns a folder from the start, an HTTP download collides with a file that is none of its business, and
 * the rename it picks has to be the name its `.part` file and resume sidecar are written under from
 * that moment on.
 *
 * Pinned here because the choice is silent otherwise - the bytes arrive, the state reads Completed,
 * and the only evidence the download did anything at all is a numbered file the list never mentions.
 */
class HttpConflictRenameTest {

    private val dir: Path = Files.createTempDirectory("blaze-http-conflict")
    private val storage = DefaultFileStorage()
    private val client = HttpClient(CIO)

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private fun settings(behavior: FileConflictBehavior): EngineSettingsRepository =
        EngineSettingsRepository(dir).apply {
            updateSettingsInMemory { it.copy(fileConflictBehavior = behavior) }
        }

    /** Writes a rival file under [name] and returns the destination the download will be pointed at. */
    private fun occupied(name: String, marker: String = "an earlier download"): Path {
        val destination = dir.resolve(name)
        Files.writeString(destination, marker)
        return destination
    }

    private class Run(val tasks: List<DownloadTask>, val destination: Path)

    private suspend fun transfer(
        url: String,
        name: String,
        behavior: FileConflictBehavior,
    ): Run {
        val destination = dir.resolve(name)
        val request = DownloadRequest.Http(name = name, url = url, destination = destination)
        val task = DownloadTask(DownloadId.generate(), name, request, DownloadState.Queued, null, 0, 0)
        val executor = DownloadExecutorImpl(
            initialTask = task,
            httpClient = client,
            storage = storage,
            settingsRepository = settings(behavior),
            retryPolicy = DefaultRetryPolicy(DownloadSettings()),
            onMetadataResolved = { _, _ -> },
        )
        val tasks = ArrayList<DownloadTask>()
        executor.execute().collect { tasks.add(it) }
        return Run(tasks, destination)
    }

    @Test
    fun `a taken file name sends the transfer to a numbered copy, and the row reads that too`(): Unit = runBlocking {
        val payload = TestHttpServer.of(256 * 1024, seed = 5)
        val taken = occupied("movie.mkv")
        TestHttpServer(payload).use { server ->
            val run = transfer(server.url, "movie.mkv", FileConflictBehavior.RENAME)

            val finished = run.tasks.last()
            assertIs<DownloadState.Completed>(finished.state)
            assertEquals("movie (1).mkv", (finished.request as DownloadRequest.Http).destination.fileName.toString())
            assertEquals("movie (1).mkv", finished.name, "a rename the list cannot see is a rename that did not happen")

            val renamed = dir.resolve("movie (1).mkv")
            assertBytes(payload, renamed)
            assertEquals(payload.size.toLong(), Files.size(renamed))
            assertEquals("an earlier download", Files.readString(taken), "the file already there is not this download's to touch")
        }
    }

    @Test
    fun `asking ends in the same numbered copy, because the caller was just offered that choice`(): Unit = runBlocking {
        val payload = TestHttpServer.of(256 * 1024, seed = 6)
        occupied("movie.mkv")
        TestHttpServer(payload).use { server ->
            val run = transfer(server.url, "movie.mkv", FileConflictBehavior.ASK)

            assertEquals("movie (1).mkv", run.tasks.last().name)
            assertBytes(payload, dir.resolve("movie (1).mkv"))
        }
    }

    @Test
    fun `the copy takes the first free number, not the first number`(): Unit = runBlocking {
        val payload = TestHttpServer.of(256 * 1024, seed = 7)
        occupied("movie.mkv")
        occupied(CopyName.of("movie", 1) + ".mkv")
        TestHttpServer(payload).use { server ->
            val run = transfer(server.url, "movie.mkv", FileConflictBehavior.RENAME)

            val finished = run.tasks.last()
            assertIs<DownloadState.Completed>(finished.state)
            assertEquals("movie (2).mkv", finished.name)
            assertBytes(payload, dir.resolve("movie (2).mkv"))
        }
    }

    @Test
    fun `the renamed download writes its work files under the name it actually got`(): Unit = runBlocking {
        // The numbered name is chosen before the transfer starts, so the partial and the resume
        // sidecar are keyed on it - otherwise a retry would look for work under a name this
        // download was never given and start over from byte zero.
        val payload = TestHttpServer.of(256 * 1024, seed = 8)
        val renamed = occupied("retry.mkv").resolveSibling("retry (1).mkv")
        TestHttpServer(payload).use { server ->
            val run = transfer(server.url, "retry.mkv", FileConflictBehavior.RENAME)

            assertIs<DownloadState.Completed>(run.tasks.last().state)
            val partial = storage.getPartialFile(renamed).toPath()
            val sidecar = storage.getResumeStateFile(renamed).toPath()
            assertFalse(Files.exists(partial), "the partial under the new name should have been moved into place")
            assertFalse(Files.exists(sidecar), "no sidecar may outlive a finished download")
            assertBytes(payload, renamed)
            assertEquals(
                renamed,
                (run.tasks.last().request as DownloadRequest.Http).destination,
                "the transfer ran under a name the task never learned",
            )
        }
    }

    @Test
    fun `overwriting is the one policy that replaces what was already there`(): Unit = runBlocking {
        val payload = TestHttpServer.of(256 * 1024, seed = 11)
        val rival = occupied("movie.mkv")
        TestHttpServer(payload).use { server ->
            val run = transfer(server.url, "movie.mkv", FileConflictBehavior.OVERWRITE)

            assertIs<DownloadState.Completed>(run.tasks.last().state)
            assertBytes(payload, rival)
            assertFalse(Files.exists(dir.resolve("movie (1).mkv")), "an overwrite must not also make a copy")
        }
    }

    @Test
    fun `skipping leaves the file alone and calls the download finished`(): Unit = runBlocking {
        val payload = TestHttpServer.of(256 * 1024, seed = 12)
        val rival = occupied("movie.mkv", marker = "an earlier download")
        TestHttpServer(payload).use { server ->
            val run = transfer(server.url, "movie.mkv", FileConflictBehavior.SKIP)

            val finished = run.tasks.last()
            assertIs<DownloadState.Completed>(finished.state)
            assertEquals("an earlier download", Files.readString(rival))
            assertFalse(Files.exists(dir.resolve("movie (1).mkv")))
            assertEquals(0L, server.bytesServed.get(), "a skipped download should not have asked for the body")
        }
    }

    @Test
    fun `a name that is free is kept, label and all`(): Unit = runBlocking {
        val payload = TestHttpServer.of(256 * 1024, seed = 9)
        TestHttpServer(payload).use { server ->
            val run = transfer(server.url, "fresh.mkv", FileConflictBehavior.RENAME)

            val finished = run.tasks.last()
            assertIs<DownloadState.Completed>(finished.state)
            assertEquals("fresh.mkv", finished.name)
            assertEquals(run.destination, (finished.request as DownloadRequest.Http).destination)
            assertBytes(payload, run.destination)
        }
    }

    @Test
    fun `a file with no extension is still numbered as a copy of itself`(): Unit = runBlocking {
        val payload = TestHttpServer.of(256 * 1024, seed = 10)
        occupied("LICENSE")
        TestHttpServer(payload).use { server ->
            val run = transfer(server.url, "LICENSE", FileConflictBehavior.RENAME)

            assertEquals("LICENSE (1)", run.tasks.last().name)
            assertBytes(payload, dir.resolve("LICENSE (1)"))
        }
    }

    private fun assertBytes(expected: ByteArray, path: Path) {
        assertTrue(Files.exists(path), "no file at ${path.fileName}")
        val actual = Files.readAllBytes(path)
        assertEquals(expected.size, actual.size, "byte count of ${path.fileName}")
        var mismatch = -1
        for (index in expected.indices) {
            if (expected[index] != actual[index]) {
                mismatch = index
                break
            }
        }
        assertTrue(mismatch < 0, "content of ${path.fileName} differs starting at byte $mismatch")
    }
}
