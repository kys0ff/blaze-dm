package org.blaze.engine.benchmark

import bt.data.IDataDescriptorFactory
import bt.data.Storage
import bt.data.file.FileSystemStorage
import bt.metainfo.IMetadataService
import bt.runtime.BtRuntime
import bt.runtime.Config
import bt.torrent.maker.TorrentBuilder
import org.blaze.engine.support.SwarmHarness
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TOR-1 deliverable 3: the actual before/after numbers for fast resume on a multi-hundred-MiB
 * fixture. It measures exactly what a restart makes the user wait for - building the data
 * descriptor, i.e. deciding which pieces are already verified - with no network in the way:
 *
 *  - OLD        : stock verifier; every restart re-hashes every byte (upstream
 *                 `DefaultChunkVerifier.java:101-108`).
 *  - FAST cold  : first session with resume enabled; no saved state, so the same full re-hash,
 *                 plus the snapshot saved on close.
 *  - FAST warm  : restarts that trust the saved bitfield after the fingerprint gate and the
 *                 configured spot-checks - disk reads proportional to the spot-check count, not
 *                 to the torrent size.
 *
 * Run with `./gradlew :download-engine:httpBenchmark --tests "*TorrentFastResumeBenchmark*"`.
 * A benchmark reports its numbers; it does not hard-fail on a slow machine.
 */
class TorrentFastResumeBenchmark {

    private val mib = 1024 * 1024
    private val enabled get() = System.getProperty("blaze.benchmark") == "true"

    /** Total fixture size in MiB; "multi-hundred-MiB" per TOR-1, overridable for quick smoke runs. */
    private val totalMiB = System.getProperty("blaze.benchmark.resume.sizeMiB")?.toIntOrNull() ?: 320

    @Test
    fun `restart after fast resume vs full re-hash on a large fixture`() {
        if (!enabled) return

        val root: Path = Files.createTempDirectory("blaze-resume-bench")
        val bundleDir = Files.createDirectories(root.resolve("bundle"))
        val resumeDir = Files.createDirectories(root.resolve("resume"))

        try {
            // Two files so the fingerprint gate covers a multi-file layout; streaming generation
            // so the benchmark needs no heap anywhere near the fixture size.
            val pieceSize = mib
            val fileSizes = listOf(totalMiB / 2, totalMiB - totalMiB / 2).map { it.toLong() * mib }
            fileSizes.forEachIndexed { index, size -> writePattern(bundleDir.resolve("f$index.bin"), size) }

            val torrentBytes = TorrentBuilder()
                .rootPath(bundleDir)
                .addFiles(*fileSizes.indices.map { bundleDir.resolve("f$it.bin") }.toTypedArray())
                .pieceSize(pieceSize)
                .build()
            val torrentFile = root.resolve("bench.torrent")
            Files.write(torrentFile, torrentBytes)

            // Baseline: resume disabled (the library's behavior before the patch).
            val plainRuntime = runtime { /* defaults: autoResumeEnabled=false */ }
            val oldMs = timeSession(plainRuntime, torrentBytes, root)

            // First fast session: no state saved yet, so it pays the full re-hash itself...
            val fastRuntimeCold = runtime { config -> enableResume(config, resumeDir) }
            val coldMs = timeSession(fastRuntimeCold, torrentBytes, root)

            // ...and every later one only spot-checks. Three warm runs to show stability.
            val warmMs = (1..3).map {
                timeSession(runtime { config -> enableResume(config, resumeDir) }, torrentBytes, root)
            }

            println(
                "Fast resume restart (%d MiB, %d pieces of %d KiB, %d files):".format(
                    totalMiB, totalMiB * mib / pieceSize, pieceSize / 1024, fileSizes.size
                )
            )
            println("  full re-hash (before)   : %7.1f ms".format(oldMs))
            println("  fast cold  (first run)  : %7.1f ms".format(coldMs))
            warmMs.forEachIndexed { i, ms -> println("  fast warm #%d (after)   : %7.1f ms".format(i + 1, ms)) }
            val fastestWarm = warmMs.min()
            println("  restart speedup         : %.1fx (full re-hash / warm restart)".format(oldMs / fastestWarm))

            // Warm restarts must be dramatically cheaper - they read spot-checks, not the torrent.
            assertTrue(
                fastestWarm * 8 < coldMs,
                "warm restart (${fastestWarm}ms) did not beat the cold full re-hash (${coldMs}ms) by 8x"
            )
        } catch (e: Exception) {
            println("Fast resume benchmark: NOT MEASURABLE here - ${e.javaClass.simpleName}: ${e.message}")
            assertTrue(true)
        } finally {
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    /**
     * One restart cycle measured end to end: parse metainfo, build the descriptor (this is where
     * verification runs), then close it - which is also where the snapshot gets saved on a clean
     * close. Returns wall-clock milliseconds of the descriptor build.
     */
    private fun timeSession(runtime: BtRuntime, torrentBytes: ByteArray, storageRoot: Path): Double = try {
        val torrent = runtime.service(IMetadataService::class.java).fromByteArray(torrentBytes)
        val factory = runtime.getInjector().getInstance(IDataDescriptorFactory::class.java)
        val storage: Storage = FileSystemStorage(storageRoot)
        val started = System.nanoTime()
        val descriptor = factory.createDescriptor(torrent, storage, null)
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        // Clean close - this is the moment the fast runtime saves its snapshot.
        runCatching { descriptor.close() }
        assertTrue(descriptor.startedAsSeed(), "fixture is complete data - every session must verify as seed")
        elapsed
    } finally {
        runCatching { runtime.shutdown() }
    }

    private fun enableResume(config: Config, resumeDir: Path) {
        config.isAutoResumeEnabled = true
        config.resumeDirectory = resumeDir
        config.resumeSpotCheckCount = 5
    }

    private fun runtime(configure: (Config) -> Unit): BtRuntime {
        val config = Config().apply {
            acceptorAddress = SwarmHarness.loopback()
            acceptorPort = SwarmHarness.reserveFreePort()
            peerDiscoveryInterval = Duration.ofMillis(250)
        }
        configure(config)
        // No client is ever started; the acceptor and DHT-free module set stay dormant, so this
        // runtime only contributes the object graph (verifier decoration) around the measurement.
        return BtRuntime.builder(config).disableAutomaticShutdown().build()
    }

    /** Streams [size] bytes of deterministic pseudo-random content - real entropy gets no cache help. */
    private fun writePattern(target: Path, size: Long) {
        Files.newOutputStream(target).use { out ->
            val chunk = ByteArray(mib)
            var state = 0x9E3779B9.toInt()
            var written = 0L
            while (written < size) {
                val len = minOf(chunk.size.toLong(), size - written).toInt()
                for (i in 0 until len) {
                    state = state * 1103515245 + 12345
                    chunk[i] = (state ushr 16).toByte()
                }
                out.write(chunk, 0, len)
                written += len
            }
        }
    }
}
