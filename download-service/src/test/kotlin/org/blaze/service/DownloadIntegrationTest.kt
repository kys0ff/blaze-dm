package org.blaze.service

import kotlinx.coroutines.*
import org.blaze.download.api.DownloadSource
import org.blaze.download.api.DownloadState
import org.blaze.download.api.NewDownloadRequest
import org.blaze.download.api.ServicePaths
import org.blaze.service.auth.ApiTokenStore
import org.blaze.service.config.LocalApiSettings
import org.blaze.service.support.TestSourceServer
import org.blaze.service.support.payloadOf
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Proves the whole vertical slice really moves bytes: `HTTP transport -> Application API -> engine`, and
 * the in-process `Application API -> engine`, against the *actual* [org.blaze.engine.core.DownloadManager]
 * downloading from a real loopback file server - no mocks.
 *
 * It also asserts the two hardest guarantees end to end: a create succeeds only with the right token, and
 * a transfer credential is never written to the on-disk store (§11, §27).
 */
class DownloadIntegrationTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()

    @AfterTest
    fun teardown() = job.cancel()

    private fun tempDir(): Path = Files.createTempDirectory("blaze-it")

    private fun hostWithStorage(downloadsRoot: Path, requireAuth: Boolean): Pair<DownloaderServiceHost, Path> {
        val storage = tempDir()
        val settings = LocalApiSettings(
            enabled = true, requireAuth = requireAuth, port = 0,
            allowedDownloadRoots = listOf(downloadsRoot.toString()), eventMinIntervalMillis = 0,
        )
        return DownloaderServiceHost.create(storage, scope, settings, startServer = true) to storage
    }

    private suspend fun awaitTerminal(deadlineMs: Long = 40_000, fetch: suspend () -> DownloadState?): DownloadState {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < deadlineMs) {
            val s = fetch()
            if (s != null && s.isTerminal) return s
            delay(150)
        }
        return fetch() ?: DownloadState.FAILED
    }

    private fun stateRegex() = Regex("\"state\":\"([^\"]+)\"")
    private fun idRegex() = Regex("\"id\":\"([^\"]+)\"")

    @Test
    fun `application api drives a real engine download to completion`() = runBlocking<Unit> {
        val downloads = tempDir()
        val payload = payloadOf(120_000)
        TestSourceServer(payload).use { source ->
            val (host, _) = hostWithStorage(downloads, requireAuth = true)
            try {
                val dto = host.api.create(
                    NewDownloadRequest(
                        source = DownloadSource.Url(source.url("/big.bin")),
                        destination = downloads.toString(),
                        filename = "big.bin",
                    )
                )
                assertEquals(DownloadState.COMPLETED, awaitTerminal { host.api.get(dto.id)?.state })

                val written = Files.walk(downloads).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
                assertTrue(written.isNotEmpty(), "expected the file to be written")
                val bytes = written.maxByOrNull { Files.size(it) }!!.let { Files.readAllBytes(it) }
                assertArrayEqualsContent(payload, bytes)
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `http api submits and observes a real download end to end`() = runBlocking<Unit> {
        val downloads = tempDir()
        val payload = payloadOf(80_000)
        TestSourceServer(payload).use { source ->
            val (host, storage) = hostWithStorage(downloads, requireAuth = true)
            try {
                val token = ApiTokenStore(ServicePaths.tokenFileFor(storage)).token()
                val base = "http://127.0.0.1:${host.boundPort}"
                val body =
                    """{"url":"${source.url("/dl.bin")}","destination":"${downloads.asJsonPath()}","filename":"dl.bin"}"""
                val created = http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/v1/downloads"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $token")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(201, created.statusCode())
                val id = idRegex().find(created.body())!!.groupValues[1]

                val finalState = awaitTerminal {
                    val info = http.send(
                        HttpRequest.newBuilder(URI.create("$base/api/v1/downloads/$id"))
                            .header("Authorization", "Bearer $token").GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    stateRegex().find(info.body())?.groupValues?.get(1)?.let { DownloadState.valueOf(it) }
                }
                assertEquals(DownloadState.COMPLETED, finalState)

                val written = Files.walk(downloads).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
                assertTrue(written.any { Files.size(it) == payload.size.toLong() }, "downloaded file should match source size")
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `credentials used for a real transfer are never persisted to disk`() = runBlocking<Unit> {
        val downloads = tempDir()
        TestSourceServer(payloadOf(4096), requireAuthorization = "Bearer live-secret").use { source ->
            val (host, storage) = hostWithStorage(downloads, requireAuth = true)
            try {
                val base = "http://127.0.0.1:${host.boundPort}"
                val token = ApiTokenStore(ServicePaths.tokenFileFor(storage)).token()
                val body =
                    """{"url":"${source.url("/auth.bin")}","destination":"${downloads.asJsonPath()}","filename":"auth.bin","credentials":{"Authorization":"Bearer live-secret"}}"""
                val created = http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/v1/downloads"))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer $token")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(201, created.statusCode())
                // The response echoed to the client must not carry the secret either.
                assertFalse("live-secret" in created.body(), "create response leaked the credential")

                val id = idRegex().find(created.body())!!.groupValues[1]
                awaitTerminal { host.api.get(id)?.state }

                // Nothing in the service's on-disk state (db, settings, descriptor) may contain the secret.
                val onDisk = Files.walk(storage).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .map { runCatching { Files.readString(it) }.getOrDefault("") }
                        .toList()
                }.joinToString("\n")
                assertFalse("live-secret" in onDisk, "credential was persisted to the download database")
            } finally {
                host.close()
            }
        }
    }

    private fun assertArrayEqualsContent(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "downloaded size differs")
        for (i in expected.indices) assertEquals(expected[i], actual[i], "byte mismatch at $i")
    }

    /** The destination path rendered as valid JSON string contents (Windows separators are escaped). */
    private fun Path.asJsonPath(): String = toString().replace("\\", "\\\\")
}
