package org.blaze.engine.portable

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.execution.HttpDownloadCoordinator
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.storage.FileStorage
import org.blaze.engine.support.TestHttpServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The end-to-end promise of the feature: download part of a file on "machine A", move *only* the
 * incomplete artifact to a fresh directory ("machine B"), and resume from that single file — with no
 * sidecar, no database and no original machine state — into a byte-for-byte correct final file whose
 * portable region has been stripped.
 *
 * The "machine" boundary is simulated by a second temp directory and a copy that deliberately omits
 * the `.meta` sidecar; the resume must come entirely from the embedded manifest.
 */
class HttpPortableResumeTest {

    private val mib = 1024 * 1024
    private val storage: FileStorage = DefaultFileStorage()
    private val dirA: Path = Files.createTempDirectory("blaze-portable-a")
    private val dirB: Path = Files.createTempDirectory("blaze-portable-b")

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
        followRedirects = false
    }

    @AfterTest
    fun tearDown() {
        client.close()
        dirA.toFile().deleteRecursively()
        dirB.toFile().deleteRecursively()
    }

    private fun coordinator() = HttpDownloadCoordinator(
        client = HttpNetworkClient(client),
        storage = storage,
        limiter = BandwidthLimiter(),
        settings = DownloadSettings()
    )

    private suspend fun download(destination: Path, url: String, connections: Int = 4): HttpDownloadCoordinator.Outcome {
        val name = destination.fileName.toString()
        val partial = storage.getPartialFile(destination).toPath()
        val outcome = coordinator().download(
            request = DownloadRequest.Http(name = name, url = url, destination = destination),
            destination = destination,
            partial = partial,
            connections = connections
        ) { }
        if (outcome is HttpDownloadCoordinator.Outcome.Success) storage.move(partial, destination)
        return outcome
    }

    @Test
    fun `a moved partial resumes from its embedded region alone and finalizes byte-exact`(): Unit = runBlocking {
        val payload = TestHttpServer.of(16 * mib, seed = 99)
        val name = "portable.bin"

        // ---- machine A: fail partway so a chunk bitmap + portable region survive.
        TestHttpServer(payload, earlyCloseAfterBytes = 9L * mib, validator = "\"v1\"").use { server ->
            val destA = dirA.resolve(name)
            assertIs<HttpDownloadCoordinator.Outcome.Failure>(download(destA, server.url))

            val partialA = storage.getPartialFile(destA).toPath()
            val artifact = assertNotNull(inspectPortableFile(partialA), "the partial must carry a portable region")
            assertEquals(PortableKind.HTTP, artifact.kind)
            assertEquals(payload.size.toLong(), artifact.contentLength)
            val done = (artifact.manifest as PortableManifest.Http).completedChunks.cardinality()
            assertTrue(done > 0, "no chunk completed before the failure; nothing to prove")
        }

        // ---- the MOVE: copy ONLY the artifact to machine B, no sidecar, different path entirely.
        val destB = dirB.resolve(name)
        val partialA = storage.getPartialFile(dirA.resolve(name)).toPath()
        val partialB = storage.getPartialFile(destB).toPath()
        Files.createDirectories(destB.parent)
        Files.copy(partialA, partialB, StandardCopyOption.REPLACE_EXISTING)
        // Deliberately do NOT copy the .meta sidecar — B must recover from the embedded manifest.
        val sidecarB = storage.getResumeStateFile(destB).toPath()
        assertTrue(!Files.exists(sidecarB), "test setup must not leak the sidecar to machine B")

        // ---- import on B: rebuild the same-machine resume state from the portable artifact.
        val imported = assertNotNull(inspectPortableFile(partialB))
        assertTrue(installHttpResumeSidecar(imported, sidecarB), "import must reconstruct the sidecar")

        // ---- resume on B: server now behaves; only the missing chunks should be fetched.
        TestHttpServer(payload, validator = "\"v1\"").use { server ->
            val before = server.bytesServed.get()
            val outcome = download(destB, server.url)
            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            val served = server.bytesServed.get() - before
            assertTrue(
                served < payload.size,
                "machine B re-downloaded the whole file ($served of ${payload.size}); the portable bitmap was ignored"
            )
        }

        // ---- the finished file is exactly the original, with no trailer and no sidecar required.
        assertEquals(payload.size.toLong(), Files.size(destB), "final length must equal the source")
        assertContentEquals(payload, Files.readAllBytes(destB), "final bytes must equal the source")
        assertNull(inspectPortableFile(destB), "the portable region must be gone from the finished file")
        assertTrue(!Files.exists(sidecarB), "the sidecar must not outlive a finished download")
    }

    @Test
    fun `a moved partial whose server changed is not blindly trusted`(): Unit = runBlocking {
        val payload = TestHttpServer.of(12 * mib, seed = 5)
        val name = "swapped.bin"

        TestHttpServer(payload, earlyCloseAfterBytes = 6L * mib, validator = "\"old\"").use { server ->
            assertIs<HttpDownloadCoordinator.Outcome.Failure>(download(dirA.resolve(name), server.url))
        }

        // Move to B, import the sidecar, but the resource now has a different ETag + different bytes.
        val destB = dirB.resolve(name)
        val partialB = storage.getPartialFile(destB).toPath()
        Files.copy(storage.getPartialFile(dirA.resolve(name)).toPath(), partialB, StandardCopyOption.REPLACE_EXISTING)
        val sidecarB = storage.getResumeStateFile(destB).toPath()
        installHttpResumeSidecar(assertNotNull(inspectPortableFile(partialB)), sidecarB)

        val changed = TestHttpServer.of(12 * mib, seed = 6)
        TestHttpServer(changed, validator = "\"new\"").use { server ->
            val before = server.bytesServed.get()
            assertIs<HttpDownloadCoordinator.Outcome.Success>(download(destB, server.url))
            // The stale chunks were rejected, so essentially the whole (new) body is re-read.
            val served = server.bytesServed.get() - before
            assertTrue(
                served >= changed.size - 1024,
                "stale portable state was reused despite the changed validator: only $served served"
            )
        }
        assertContentEquals(changed, Files.readAllBytes(destB))
        assertNull(inspectPortableFile(destB))
    }
}
