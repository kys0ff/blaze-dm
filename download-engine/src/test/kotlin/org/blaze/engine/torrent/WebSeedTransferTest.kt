package org.blaze.engine.torrent

import bt.Bt
import bt.data.file.FileSystemStorage
import bt.metainfo.MetadataService
import bt.metainfo.Torrent
import bt.runtime.BtRuntime
import bt.torrent.maker.TorrentBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.network.webseed.HttpWebSeedFetcher
import org.blaze.engine.network.webseed.WebSeedDownloader
import org.blaze.engine.support.SwarmHarness
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Closes TOR-3: the first test that proves a torrent transfers end to end from a web/HTTP seed
 * (BEP-17 / BEP-19) with no swarm at all.
 *
 * The leecher runs in an isolated [BtRuntime] on loopback (a bare bt-core runtime - no DHT, tracker,
 * PEX or multicast - so the only possible source of bytes is the seed). A real `com.sun.net.httpserver`
 * file server serves the torrent's files with HTTP byte-range support, exactly the GetRight-style
 * layout [HttpWebSeedFetcher] builds URLs for. A second server deliberately returns tampered bytes to
 * exercise BEP-19's "discard the URL on a bad hash" rule: the downloader must ban the corrupt mirror
 * and still finish with byte-perfect files from the honest one. Every file is re-verified by SHA-256 -
 * independent of the SHA-1 piece hashes `bt` checks internally.
 */
class WebSeedTransferTest {

    private val logger = LoggerFactory.getLogger(WebSeedTransferTest::class.java)

    @Test
    fun `a multi-file torrent completes from a web seed with no peers, banning the corrupt mirror`() {
        val root = Files.createTempDirectory("blaze-webseed")
        val seedDir = Files.createDirectories(root.resolve("seed"))
        val bundleDir = Files.createDirectories(seedDir.resolve("bundle"))
        val leechDir = Files.createDirectories(root.resolve("leech"))

        // ~616 KiB across three files, none but beta piece-aligned, so pieces straddle file edges.
        val sizes = linkedMapOf(
            "alpha.bin" to 200_000,
            "beta.bin" to 131_072,
            "gamma.bin" to 300_000,
        )
        val random = Random(0x5EED)
        val expectedSha256 = sizes.entries.associate { (name, size) ->
            val bytes = random.nextBytes(size)
            Files.write(bundleDir.resolve(name), bytes)
            name to bytes.sha256Hex()
        }

        val torrentBytes = TorrentBuilder()
            .rootPath(bundleDir)
            .addFiles(*sizes.keys.map { bundleDir.resolve(it) }.toTypedArray())
            .pieceSize(128 * 1024)
            .build()
        val torrentFile = Files.write(root.resolve("seed.torrent"), torrentBytes)
        val torrent: Torrent = MetadataService().fromByteArray(torrentBytes)

        val good = SeedFileServer(seedDir, corrupt = false)
        val bad = SeedFileServer(seedDir, corrupt = true)
        var leecherRuntime: BtRuntime? = SwarmHarness.seederRuntime(SwarmHarness.reserveFreePort())
        val httpClient = HttpClient(CIO)
        var client: bt.runtime.BtClient? = null
        try {
            // Start the leecher so the torrent registers and its data descriptor is created. It has no
            // peers, so nothing would ever move without the web-seed feed we drive by hand below.
            val runtime = leecherRuntime!!
            client = Bt.client(runtime)
                .storage(FileSystemStorage(leechDir))
                .torrent(torrentFile.toUri().toURL())
                .build()
            client.startAsync()

            val fetcher = HttpWebSeedFetcher(torrent, HttpNetworkClient(httpClient))
            // Corrupt mirror first: the agent must write its bad piece, watch verification fail, ban it,
            // and re-fetch the same piece from the honest mirror.
            val seeds = listOf(bad.baseUrl, good.baseUrl)
            runBlocking {
                WebSeedDownloader(runtime, torrent, fetcher, seeds, logger).run()
            }

            // Close the session so every storage channel flushes before we read the files back.
            runCatching { client?.stop() }
            runCatching { runtime.shutdown() }
            leecherRuntime = null

            sizes.forEach { (name, size) ->
                val downloaded = leechDir.resolve("bundle").resolve(name)
                assertEquals(size.toLong(), Files.size(downloaded), "size of web-seeded $name")
                assertEquals(
                    expectedSha256.getValue(name),
                    Files.readAllBytes(downloaded).sha256Hex(),
                    "SHA-256 of web-seeded $name",
                )
            }
        } finally {
            httpClient.close()
            runCatching { client?.stop() }
            leecherRuntime?.let { runCatching { it.shutdown() } }
            runCatching { good.stop() }
            runCatching { bad.stop() }
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    /**
     * A minimal loopback HTTP file server that honours `Range: bytes=start-end` with a `206`, rooted at
     * [docRoot]. When [corrupt] is set it flips bits in the first byte of every response, so any piece
     * pulled from it fails SHA-1 verification - the trigger for a web-seed ban.
     */
    private class SeedFileServer(docRoot: Path, private val corrupt: Boolean) {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val root: Path = docRoot.toAbsolutePath().normalize()

        val baseUrl: String

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.executor = null
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}/"
        }

        private fun handle(exchange: HttpExchange) {
            try {
                val rel = URLDecoder.decode(exchange.requestURI.rawPath, StandardCharsets.UTF_8).trimStart('/')
                val file = root.resolve(rel).normalize()
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1)
                    return
                }
                val all = Files.readAllBytes(file)
                val (start, end) = parseRange(exchange.requestHeaders.getFirst("Range"), all.size)
                val slice = all.copyOfRange(start, end + 1)
                if (corrupt && slice.isNotEmpty()) {
                    slice[0] = (slice[0].toInt() xor 0xFF).toByte()
                }
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${all.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } catch (e: Exception) {
                runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally {
                exchange.close()
            }
        }

        private fun parseRange(header: String?, size: Int): Pair<Int, Int> {
            if (header == null || !header.startsWith("bytes=")) return 0 to (size - 1).coerceAtLeast(0)
            val spec = header.removePrefix("bytes=").substringBefore('-')
            val from = spec.trim().toIntOrNull() ?: 0
            val toPart = header.substringAfter('-').trim()
            val to = if (toPart.isEmpty()) size - 1 else toPart.toIntOrNull() ?: (size - 1)
            return from to minOf(to, size - 1)
        }

        fun stop() = server.stop(0)
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
