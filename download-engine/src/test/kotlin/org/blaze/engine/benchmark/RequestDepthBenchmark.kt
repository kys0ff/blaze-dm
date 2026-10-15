package org.blaze.engine.benchmark

import bt.Bt
import bt.data.file.FileSystemStorage
import bt.net.InetPeer
import bt.runtime.Config
import bt.torrent.maker.TorrentBuilder
import org.blaze.engine.network.applyPerformanceConfig
import org.blaze.engine.support.LatencyProxy
import org.blaze.engine.support.SwarmHarness
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Before/after numbers for TOR-5 (adaptive request depth), the item PROMPT-2 requires be *measured*.
 *
 * It transfers the same fixture over the same shaped link (a [LatencyProxy] adding per-hop latency and
 * an optional bandwidth cap) twice — once with the fork's adaptive window on, once off (fixed 250) —
 * and prints the achieved throughput for each, so the effect of adapting depth to the measured
 * bandwidth-delay product can be observed on a high-latency and a throttled peer. A benchmark reports
 * what it observes, including "no difference" or "not measurable"; it never fabricates a win, and a
 * failed/timed-out run is printed as a negative rather than thrown. Run with
 * `./gradlew :download-engine:httpBenchmark --tests "*RequestDepthBenchmark*"`.
 */
class RequestDepthBenchmark {

    private val mib = 1024 * 1024
    private val enabled get() = System.getProperty("blaze.benchmark") == "true"

    private data class Profile(val name: String, val oneWayDelayMs: Long, val capBytesPerSec: Long)

    @Test
    fun `compare fixed and adaptive request depth over shaped links`() {
        if (!enabled) return

        val root: Path = Files.createTempDirectory("blaze-depth-bench")
        val seedDir = Files.createDirectories(root.resolve("seed"))

        val size = 8 * mib
        val data = ByteArray(size)
        var state = 0x9E3779B9
        for (i in data.indices) {
            state = state * 1103515245 + 12345
            data[i] = (state ushr 16).toByte()
        }
        val payload = Files.write(seedDir.resolve("payload.bin"), data)

        val torrentBytes = TorrentBuilder().addFile(payload).pieceSize(256 * 1024).build()
        val torrentUrl = Files.write(root.resolve("bench.torrent"), torrentBytes).toUri().toURL()

        val profiles = listOf(
            Profile("high-latency ~40ms RTT, unthrottled", oneWayDelayMs = 20, capBytesPerSec = 0),
            Profile("throttled ~20ms RTT, 4 MiB/s", oneWayDelayMs = 10, capBytesPerSec = 4L * mib),
        )

        for (profile in profiles) {
            val fixed = transfer(torrentUrl, seedDir, root, profile, adaptive = false)
            val adaptive = transfer(torrentUrl, seedDir, root, profile, adaptive = true)
            println("Request depth [${profile.name}]: fixed=${fixed.describe(mib)}  adaptive=${adaptive.describe(mib)}")
        }
        root.toFile().deleteRecursively()
    }

    private fun transfer(
        torrentUrl: java.net.URL,
        seedDir: Path,
        root: Path,
        profile: Profile,
        adaptive: Boolean,
    ): Result {
        val leechDir = Files.createDirectories(root.resolve("leech-${adaptive}-${profile.name.hashCode()}"))
        val seederPort = SwarmHarness.reserveFreePort()
        val leecherPort = SwarmHarness.reserveFreePort()
        val loopback = SwarmHarness.loopback()
        val seederRuntime = SwarmHarness.seederRuntime(seederPort)
        return try {
            LatencyProxy(seederPort, profile.oneWayDelayMs, profile.capBytesPerSec).use { proxy ->
                val leecherRuntime = SwarmHarness.leecherRuntime(
                    acceptorPort = leecherPort,
                    knownPeers = listOf(InetPeer.build(loopback, proxy.boundPort)),
                    configure = { config: Config ->
                        applyPerformanceConfig(config, maxPeerConnections = 4, bindAddress = loopback)
                        if (!adaptive) config.isAdaptiveRequestDepthEnabled = false
                    },
                )
                try {
                    val seeder = Bt.client(seederRuntime).storage(FileSystemStorage(seedDir)).torrent(torrentUrl).build()
                    val leecher = Bt.client(leecherRuntime).storage(FileSystemStorage(leechDir)).torrent(torrentUrl)
                        .stopWhenDownloaded().build()
                    seeder.startAsync()
                    val started = System.nanoTime()
                    val finished = runCatching { leecher.startAsync().get(120, TimeUnit.SECONDS) }.isSuccess
                    val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
                    val received = runCatching { findPayload(leechDir)?.let { Files.size(it) } ?: 0L }.getOrDefault(0L)
                    Result(finished = finished, bytes = received, seconds = elapsed)
                } finally {
                    runCatching { leecherRuntime.shutdown() }
                }
            }
        } finally {
            runCatching { seederRuntime.shutdown() }
        }
    }

    /** bt may nest the file under the torrent folder; locate it by name rather than assume the path. */
    private fun findPayload(dir: Path): Path? =
        Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString() == "payload.bin" }.findFirst().orElse(null) }

    private data class Result(val finished: Boolean, val bytes: Long, val seconds: Double) {
        fun describe(mib: Int): String =
            if (finished && seconds > 0 && bytes > 0) "%.2f MiB/s (%.2fs, %d MiB)".format(bytes / mib / seconds, seconds, bytes / mib)
            else "NOT MEASURED (finished=$finished, bytes=$bytes, %.1fs)".format(seconds)
    }
}
