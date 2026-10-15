package org.blaze.engine.torrent

import bt.Bt
import bt.data.file.FileSystemStorage
import bt.net.InetPeer
import bt.runtime.BtRuntime
import bt.torrent.maker.TorrentBuilder
import org.blaze.engine.network.applyPerformanceConfig
import org.blaze.engine.support.LatencyProxy
import org.blaze.engine.support.SwarmHarness
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Closes TOR-5's integration half: the adaptive request window (fork patch #8) is exercised over a
 * *real* torrent transfer through a link that actually has a bandwidth-delay product — a loopback TCP
 * proxy adding [LatencyProxy.oneWayDelayMillis] per hop and capping throughput. Unlike the pure
 * estimator test (`RequestWindowTest`), this drives the wiring in `RequestProducer`/`PieceConsumer`
 * end to end: requests are issued at an adaptive depth, latency is measured on real block arrivals,
 * and the window steers a completing download. Correctness — not timing — is what is asserted here;
 * the before/after numbers live in `RequestDepthBenchmark`.
 *
 * The leecher runs with `applyPerformanceConfig` and adaptive pipelining switched on explicitly
 * (it is opt-in in production), and reaches the seeder only through the proxy (its static peer source
 * points at the proxy's port). Everything stays on 127.0.0.1: no tracker, DHT or multicast.
 */
class AdaptiveRequestDepthTest {

    @Test
    fun `adaptive depth completes a throttled high-latency transfer with byte-perfect files`() {
        val root = Files.createTempDirectory("blaze-adaptive-depth")
        val seedDir = Files.createDirectories(root.resolve("seed"))
        val bundleDir = Files.createDirectories(seedDir.resolve("bundle"))
        val leechDir = Files.createDirectories(root.resolve("leech"))

        // ~2.4 MiB across two files, one deliberately not piece-aligned.
        val sizes = linkedMapOf(
            "alpha.bin" to 1024 * 1024 + 5_000,
            "beta.bin" to 1400 * 1024,
        )
        val random = Random(0xADAD)
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
        val torrentUrl = Files.write(root.resolve("swarm.torrent"), torrentBytes).toUri().toURL()

        val seederPort = SwarmHarness.reserveFreePort()
        val leecherPort = SwarmHarness.reserveFreePort()
        val seederRuntime: BtRuntime = SwarmHarness.seederRuntime(seederPort)
        val loopback = SwarmHarness.loopback()

        // 25 ms per hop -> ~50 ms round trip; a 3 MiB/s cap makes the BDP concrete (and small).
        LatencyProxy(seederPort, oneWayDelayMillis = 25, maxBytesPerSecond = 3L * 1024 * 1024).use { proxy ->
            val leecherRuntime = SwarmHarness.leecherRuntime(
                acceptorPort = leecherPort,
                knownPeers = listOf(InetPeer.build(loopback, proxy.boundPort)),
                configure = { config ->
                    applyPerformanceConfig(config, maxPeerConnections = 4, bindAddress = loopback)
                    // Opt-in in production (default stays the fixed 250); force it on to exercise the
                    // adaptive window's real request/receipt wiring end to end.
                    config.isAdaptiveRequestDepthEnabled = true
                },
            )
            try {
                val seeder = Bt.client(seederRuntime)
                    .storage(FileSystemStorage(seedDir))
                    .torrent(torrentUrl)
                    .build()
                val leecher = Bt.client(leecherRuntime)
                    .storage(FileSystemStorage(leechDir))
                    .torrent(torrentUrl)
                    .stopWhenDownloaded()
                    .build()

                seeder.startAsync()
                leecher.startAsync().get(150, TimeUnit.SECONDS)

                sizes.forEach { (name, size) ->
                    val downloaded = leechDir.resolve("bundle").resolve(name)
                    assertEquals(size.toLong(), Files.size(downloaded), "size of $name")
                    assertEquals(
                        expectedSha256.getValue(name),
                        Files.readAllBytes(downloaded).sha256Hex(),
                        "SHA-256 of $name",
                    )
                }
            } finally {
                runCatching { leecherRuntime.shutdown() }
            }
        }
        runCatching { seederRuntime.shutdown() }
        runCatching { root.toFile().deleteRecursively() }
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
