package org.blaze.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.blaze.download.api.DownloadDto
import org.blaze.download.api.DownloadSource
import org.blaze.download.api.DownloadState
import org.blaze.download.api.NewDownloadRequest
import org.blaze.download.api.ServicePaths
import org.blaze.service.DownloaderServiceHost
import org.blaze.service.auth.ApiTokenStore
import org.blaze.service.config.LocalApiSettings
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof of the CLI as just another client of the public API.
 *
 * A real [DownloaderServiceHost] is started (application service + actual [org.blaze.engine.core.DownloadManager]
 * + loopback HTTP transport), and the CLI is driven against it through the same discovery/protocol path a browser
 * bridge would use. This covers the contract scripts depend on - exit codes, `--json` stability, auth failure -
 * and, crucially, that `CLI -> HTTP -> Application API -> engine` really moves bytes rather than talking to a mock.
 */
class CliIntegrationTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val json = Json { ignoreUnknownKeys = true }
    private val hosts = mutableListOf<DownloaderServiceHost>()

    @AfterTest
    fun teardown() {
        hosts.forEach { runCatching { it.close() } }
        job.cancel()
    }

    private class Service(val host: DownloaderServiceHost, val base: String, val token: String, val downloads: Path)

    private fun startService(): Service {
        val storage = Files.createTempDirectory("blaze-cli-svc")
        val downloads = Files.createTempDirectory("blaze-cli-dl")
        val settings = LocalApiSettings(
            enabled = true, requireAuth = true, port = 0,
            allowedDownloadRoots = listOf(downloads.toString()), eventMinIntervalMillis = 0,
        )
        val host = DownloaderServiceHost.create(storage, scope, settings, startServer = true).also { hosts += it }
        val token = ApiTokenStore(ServicePaths.tokenFileFor(storage)).token()
        return Service(host, "http://127.0.0.1:${host.boundPort}", token, downloads)
    }

    private fun cli(svc: Service, vararg args: String): Triple<Int, String, String> {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val code = Cli().run(
            (args.toList() + listOf("--url", svc.base, "--token", svc.token)).toTypedArray(),
            PrintStream(o, true), PrintStream(e, true),
        )
        return Triple(code, o.toString(), e.toString())
    }

    private fun sourceServer(payload: ByteArray): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "cli-source").apply { isDaemon = true } }
            createContext("/") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                val from = range?.substringAfter('=')?.substringBefore('-')?.toLongOrNull() ?: 0L
                val to = (range?.substringAfter('-')?.toLongOrNull() ?: (payload.size - 1L)).coerceIn(from, (payload.size - 1).toLong())
                val slice = payload.copyOfRange(from.toInt(), (to + 1).toInt())
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/${payload.size}")
                val status = if (range != null) 206 else 200
                ex.sendResponseHeaders(status, slice.size.toLong())
                ex.responseBody.use { it.write(slice) }
            }
            start()
        }

    @Test
    fun `list json returns downloads created through the api`() {
        val svc = startService()
        val created = runBlocking {
            svc.host.api.create(
                NewDownloadRequest(
                    source = DownloadSource.Url("https://example.com/queued.bin"),
                    destination = svc.downloads.toString(), filename = "queued.bin", autoStart = false,
                )
            )
        }
        val (code, out, _) = cli(svc, "list", "--json")
        assertEquals(ExitCodes.SUCCESS, code)
        val dtos = json.decodeFromString(ListSerializer(DownloadDto.serializer()), out.trim())
        assertTrue(dtos.any { it.id == created.id }, "created download should appear in the list")
    }

    @Test
    fun `status json reports capabilities`() {
        val svc = startService()
        val (code, out, _) = cli(svc, "status", "--json")
        assertEquals(ExitCodes.SUCCESS, code)
        assertTrue("supportsTorrent" in out, "capabilities should advertise torrent support: $out")
    }

    @Test
    fun `info json returns the matching dto`() {
        val svc = startService()
        val created = runBlocking {
            svc.host.api.create(
                NewDownloadRequest(
                    source = DownloadSource.Url("https://example.com/one.bin"),
                    destination = svc.downloads.toString(), filename = "one.bin", autoStart = false,
                )
            )
        }
        val (code, out, _) = cli(svc, "info", created.id, "--json")
        assertEquals(ExitCodes.SUCCESS, code)
        val dto = json.decodeFromString(DownloadDto.serializer(), out.trim())
        assertEquals(created.id, dto.id)
    }

    @Test
    fun `pause succeeds against a live service`() {
        val svc = startService()
        val created = runBlocking {
            svc.host.api.create(
                NewDownloadRequest(
                    source = DownloadSource.Url("https://example.com/p.bin"),
                    destination = svc.downloads.toString(), filename = "p.bin", autoStart = false,
                )
            )
        }
        val (code, _, err) = cli(svc, "pause", created.id)
        assertEquals(ExitCodes.SUCCESS, code, "pause should succeed: $err")
    }

    @Test
    fun `a wrong token yields the auth-failure exit code`() {
        val svc = startService()
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val code = Cli().run(
            arrayOf("list", "--url", svc.base, "--token", "not-the-real-token"),
            PrintStream(o, true), PrintStream(e, true),
        )
        assertEquals(ExitCodes.AUTH_FAILURE, code)
    }

    @Test
    fun `download runs a real transfer end to end through the engine`() {
        val payload = ByteArray(150_000) { (it % 251).toByte() }
        val source = sourceServer(payload)
        try {
            val svc = startService()
            val url = "http://127.0.0.1:${source.address.port}/real.bin"
            val (code, out, err) = cli(svc, "download", url, "-o", svc.downloads.toString(), "--filename", "real.bin", "--json")
            assertEquals(ExitCodes.SUCCESS, code, "download command should queue successfully: $err")
            val dto = json.decodeFromString(DownloadDto.serializer(), out.trim())

            val final = runBlocking { awaitTerminal(fetch = { svc.host.api.get(dto.id)?.state }) }
            assertEquals(DownloadState.COMPLETED, final, "engine should complete the real transfer")

            val written = Files.walk(svc.downloads).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(written.any { Files.size(it) == payload.size.toLong() }, "the downloaded file should match the source size")
        } finally {
            source.stop(0)
        }
    }

    private suspend fun awaitTerminal(fetch: suspend () -> DownloadState?, deadlineMs: Long = 30_000): DownloadState {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < deadlineMs) {
            val s = fetch()
            if (s != null && s.isTerminal) return s
            delay(150)
        }
        return fetch() ?: DownloadState.FAILED
    }
}
