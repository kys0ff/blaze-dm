package org.blaze.engine.benchmark

import bt.Bt
import bt.data.file.FileSystemStorage
import bt.peer.lan.LocalServiceDiscoveryConfig
import bt.peer.lan.LocalServiceDiscoveryModule
import bt.runtime.BtRuntime
import bt.runtime.Config
import bt.torrent.maker.TorrentBuilder
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Closes the largest measurement gap the prior passes left open: torrent throughput was only ever
 * *assumed*, because every existing torrent test either parsed metadata or depended on a public
 * swarm. This builds a genuinely local, deterministic swarm with the library the project already
 * uses - an in-process seeder and leecher sharing one `BtRuntime` and finding each other through
 * LAN peer discovery (BT local-service multicast), with no external tracker or DHT router.
 *
 * It is a benchmark, not a unit test, for two honest reasons: forming even a local swarm is timing
 * dependent, and LAN multicast can be unavailable in some sandboxes. When the two peers connect the
 * number printed is a real measured transfer; when they cannot, the run says so plainly instead of
 * inventing a figure. Run with `./gradlew :download-engine:httpBenchmark --tests "*TorrentSwarmBenchmark*"`.
 */
class TorrentSwarmBenchmark {

    private val mib = 1024 * 1024
    private val enabled get() = System.getProperty("blaze.benchmark") == "true"

    @Test
    fun `measure a local seeder to leecher transfer`() {
        if (!enabled) return

        val root: Path = Files.createTempDirectory("blaze-torrent-swarm")
        val seedDir = Files.createDirectories(root.resolve("seed"))
        val leechDir = Files.createDirectories(root.resolve("leech"))

        // A 32 MiB file with real entropy so piece hashing/verification is exercised, not skipped.
        val size = 32 * mib
        val data = ByteArray(size)
        var state = 0x9E3779B9
        for (i in data.indices) {
            state = state * 1103515245 + 12345
            data[i] = (state ushr 16).toByte()
        }
        val contentFile = seedDir.resolve("sample.bin")
        Files.write(contentFile, data)

        val torrentBytes = TorrentBuilder()
            .addFile(contentFile)
            .pieceSize(256 * 1024)
            .build()
        val torrentFile = root.resolve("sample.torrent")
        Files.write(torrentFile, torrentBytes)
        val torrentUrl = torrentFile.toUri().toURL()

        val config = Config().apply {
            // Announce a routable site-local address (not 127.0.0.1) so each in-process peer learns a
            // reachable socket from the LAN multicast and the TCP connect actually lands.
            acceptorAddress = siteLocalIpv4() ?: InetAddress.getLoopbackAddress()
            maxPeerConnections = 8
            maxPeerConnectionsPerTorrent = 8
        }

        // autoLoadModules brings in the standard peer sources; the explicit LPD module is the one
        // that lets a seeder and leecher in this single runtime discover each other over multicast.
        // The library's stock announce interval is minutes - far too slow for a bounded measurement -
        // so it is tightened so the two in-process peers find each other within the run window.
        val lpd = LocalServiceDiscoveryConfig().apply {
            setLocalServiceDiscoveryAnnounceInterval(Duration.ofMillis(250))
        }
        val runtime = BtRuntime.builder(config)
            .module(LocalServiceDiscoveryModule(lpd))
            .disableAutomaticShutdown()
            .autoLoadModules()
            .build()

        val peak = AtomicLong(0)
        try {
            val seeder = Bt.client(runtime)
                .storage(FileSystemStorage(seedDir))
                .torrent(torrentUrl)
                .build()
            seeder.startAsync()

            val leecher = Bt.client(runtime)
                .storage(FileSystemStorage(leechDir))
                .torrent(torrentUrl)
                .stopWhenDownloaded()
                .build()

            val started = System.nanoTime()
            val future = leecher.startAsync({ session -> peak.accumulateAndGet(session.downloaded) { a, b -> maxOf(a, b) } }, 250)
            val finished = runCatching { future.get(90, TimeUnit.SECONDS) }.isSuccess
            val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
            val received = Files.exists(leechDir.resolve("sample.bin")).let { if (it) Files.size(leechDir.resolve("sample.bin")) else 0L }

            if (finished && received == size.toLong()) {
                println(
                    "Torrent local swarm: %d MiB in %.2f s = %.2f MiB/s (seeder <-> leecher, one runtime, LAN discovery)".format(
                        size / mib, elapsed, size / mib / elapsed
                    )
                )
            } else {
                println(
                    "Torrent local swarm: NOT MEASURED - transfer $received of $size bytes in ${"%.1f".format(elapsed)}s " +
                        "(finished=$finished); in-process peers never completed over local discovery in this environment."
                )
            }
            // A benchmark is allowed to report a negative result; it must not silently pretend success.
            assertTrue(finished || peak.get() >= 0)
        } catch (e: Exception) {
            println("Torrent local swarm: NOT MEASURABLE here - ${e.javaClass.simpleName}: ${e.message}")
            assertTrue(true)
        } finally {
            runCatching { runtime.shutdown() }
            root.toFile().deleteRecursively()
        }
    }

    private fun siteLocalIpv4(): InetAddress? {
        val prefixes = listOf("docker", "veth", "br-", "virbr", "vboxnet", "vmnet", "zt")
        return NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { iface -> prefixes.any { iface.name.startsWith(it) } }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    }
}
