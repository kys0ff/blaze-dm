package org.blaze.engine.torrent

import bt.Bt
import bt.data.Storage
import bt.data.StorageUnit
import bt.data.file.FileSystemStorage
import bt.metainfo.Torrent
import bt.metainfo.TorrentFile
import bt.net.InetPeer
import bt.net.buffer.ByteBufferView
import bt.runtime.BtRuntime
import bt.torrent.maker.TorrentBuilder
import org.blaze.engine.storage.TorrentContentStorage
import org.blaze.engine.support.SwarmHarness
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closes the engine side of TOR-1 (fork PATCH-4): fast-resume state must survive real sessions on
 * real files through the engine's own [TorrentContentStorage], and must NEVER be trusted silently.
 *
 * Every scenario PROMPT-2 names is driven against an actual local swarm (see [SwarmHarness]):
 * restart after a clean stop, restart after an interrupted (crash-like) session, a tampered file
 * with size and mtime preserved, a truncated file, and a touched mtime. The discriminator between
 * "trusted the saved bitfield" and "fell back to the full re-hash" is how many bytes the leecher
 * reads from disk during startup: a trusted session reads only its spot-checks, a full re-hash
 * reads the entire torrent. Verification itself stays bt's (SHA-1 per piece); every assertion on
 * content here is an independent SHA-256 over the whole file.
 */
class TorrentFastResumeTest {

    private companion object {
        const val PIECE_SIZE = 256 * 1024L
        val SIZES = linkedMapOf("alpha.bin" to 1024 * 1024 + 12_345, "beta.bin" to 512 * 1024 + 9_999)
        val TOTAL_SIZE: Long = SIZES.values.sum().toLong()
        val PIECES_TOTAL: Int = ((TOTAL_SIZE + PIECE_SIZE - 1) / PIECE_SIZE).toInt()
    }

    @Test
    fun `a clean stop saves resume state and the restart trusts it instead of re-hashing everything`() {
        newFixture().use { f ->
            val first = f.runLeecher(spotChecks = 1)
            f.assertDownloadedCorrect()
            assertTrue(
                first >= TOTAL_SIZE,
                "a fresh session must verify every downloaded piece ($first < $TOTAL_SIZE)"
            )

            // The clean close (client stopped via stopWhenDownloaded → registry unregister →
            // descriptor close) must have written exactly one resume file for this torrent.
            val resumeFiles = f.listResumeFiles()
            assertEquals(1, resumeFiles.size, "one resume file after a clean close")

            // A brand-new runtime over the same files: one spot-check is all it may read.
            val second = f.runLeecher(spotChecks = 1)
            f.assertDownloadedCorrect()
            assertTrue(
                second <= 2 * PIECE_SIZE,
                "restart read $second bytes - far more than a spot-check, resume was not trusted"
            )
        }
    }

    @Test
    fun `a tampered file with preserved size and mtime is caught and healed from the seeder`() {
        newFixture().use { f ->
            f.runLeecher(spotChecks = 1)
            f.assertDownloadedCorrect()

            // Flip bytes mid-file, then restore the modification time - the fingerprint gate alone
            // cannot see this; only re-reading piece data can.
            val target = f.leechDir.resolve("alpha.bin")
            val mtime = Files.getLastModifiedTime(target)
            val bytes = Files.readAllBytes(target)
            for (i in 1024 until 2048) bytes[i] = (bytes[i].toInt() xor 0xFF).toByte()
            Files.write(target, bytes)
            Files.setLastModifiedTime(target, mtime)

            // Every piece is spot-checked, so the damage cannot hide behind a lucky shuffle.
            val restarted = f.runLeecher(spotChecks = PIECES_TOTAL)
            f.assertDownloadedCorrect()
            assertTrue(
                restarted >= TOTAL_SIZE,
                "a caught tamper must fall back to the full re-hash (read only $restarted bytes)"
            )
        }
    }

    @Test
    fun `a truncated file fails the size fingerprint and the torrent still completes correctly`() {
        newFixture().use { f ->
            f.runLeecher(spotChecks = 1)
            f.assertDownloadedCorrect()

            val target = f.leechDir.resolve("beta.bin")
            val keep = Files.readAllBytes(target).copyOf((Files.size(target) / 2).toInt())
            Files.write(target, keep)

            f.runLeecher(spotChecks = 1)
            f.assertDownloadedCorrect() // the missing half was re-downloaded, not trusted
        }
    }

    @Test
    fun `a changed mtime alone refuses the saved state and re-verifies the intact data`() {
        newFixture().use { f ->
            f.runLeecher(spotChecks = 1)
            f.assertDownloadedCorrect()

            // Contents untouched; only the clock moves. The fingerprint gate must reject.
            val target = f.leechDir.resolve("alpha.bin")
            val mtime = Files.getLastModifiedTime(target)
            Files.setLastModifiedTime(target, FileTime.fromMillis(mtime.toMillis() + 5_000))

            val restarted = f.runLeecher(spotChecks = 1)
            f.assertDownloadedCorrect()
            assertTrue(
                restarted >= TOTAL_SIZE,
                "mtime mismatch must trigger a full re-hash (read only $restarted bytes)"
            )
        }
    }

    @Test
    fun `a session interrupted mid-download restarts to a correct transfer`() {
        newFixture().use { f ->
            // Start a leecher and kill its runtime out from under it without ever stopping the
            // client - the closest in-process emulation of losing the JVM mid-transfer. Whatever
            // partial state (files, sidecar) this leaves, the restart must end up correct.
            f.startLeecher(spotChecks = 1).abortAfterSomeProgress()

            f.runLeecher(spotChecks = 1)
            f.assertDownloadedCorrect()
        }
    }

    // ------------------------------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------------------------------

    /** One seeder + one empty leech dir + one resume dir, all under a throwaway temp root. */
    private class Fixture(val root: Path) : AutoCloseable {
        val seedDir = Files.createDirectories(root.resolve("seed"))
        val bundleDir = Files.createDirectories(root.resolve("seed/bundle"))
        val leechDir = Files.createDirectories(root.resolve("leech"))
        val resumeDir = Files.createDirectories(root.resolve("resume"))
        val expectedSha256: Map<String, String>
        val torrentUrl: URL

        private val seederPort = SwarmHarness.reserveFreePort()
        private val seederRuntime = SwarmHarness.seederRuntime(seederPort)

        init {
            val random = Random(0xFEED)
            expectedSha256 = SIZES.entries.associate { (name, size) ->
                val bytes = random.nextBytes(size)
                Files.write(bundleDir.resolve(name), bytes)
                name to bytes.sha256Hex()
            }
            val torrentBytes = TorrentBuilder()
                .rootPath(bundleDir)
                .addFiles(*SIZES.keys.map { bundleDir.resolve(it) }.toTypedArray())
                .pieceSize(PIECE_SIZE.toInt())
                .build()
            val torrentFile = root.resolve("fast-resume.torrent")
            Files.write(torrentFile, torrentBytes)
            torrentUrl = torrentFile.toUri().toURL()

            Bt.client(seederRuntime)
                .storage(FileSystemStorage(seedDir))
                .torrent(torrentUrl)
                .build()
                .startAsync()
        }

        /** Runs one complete leeching session (clean stop) and returns the bytes it read from disk. */
        fun runLeecher(spotChecks: Int): Long {
            val session = startLeecher(spotChecks)
            try {
                assertTrue(
                    session.done.await(90, TimeUnit.SECONDS),
                    "leecher did not finish its session in time"
                )
            } finally {
                session.shutdown()
            }
            return session.storage.bytesRead.get()
        }

        /** Launches a leecher the test controls; [RunningLeecher.abort] simulates a hard kill. */
        fun startLeecher(spotChecks: Int): RunningLeecher {
            val runtime = SwarmHarness.leecherRuntime(
                SwarmHarness.reserveFreePort(),
                listOf(InetPeer.build(SwarmHarness.loopback(), seederPort))
            ) { config ->
                config.isAutoResumeEnabled = true
                config.resumeDirectory = resumeDir
                config.resumeSpotCheckCount = spotChecks
            }
            val storage = CountingStorage(TorrentContentStorage(leechDir))
            val client = Bt.client(runtime)
                .storage(storage)
                .torrent(torrentUrl)
                .stopWhenDownloaded()
                .build()
            val done = CountDownLatch(1)
            // startAsync's completion callback fires when the client stops (normally or not).
            client.startAsync().whenComplete { _, _ -> done.countDown() }
            return RunningLeecher(runtime, storage, done)
        }

        fun listResumeFiles(): List<Path> =
            Files.list(resumeDir).use { it.filter { p -> p.fileName.toString().endsWith(".resume") }.toList() }

        fun assertDownloadedCorrect() {
            SIZES.forEach { (name, size) ->
                val downloaded = leechDir.resolve(name)
                assertEquals(size.toLong(), Files.size(downloaded), "size of downloaded $name")
                assertEquals(
                    expectedSha256.getValue(name),
                    Files.readAllBytes(downloaded).sha256Hex(),
                    "SHA-256 of downloaded $name"
                )
            }
        }

        override fun close() {
            runCatching { seederRuntime.shutdown() }
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    private class RunningLeecher(
        private val runtime: BtRuntime,
        val storage: CountingStorage,
        val done: CountDownLatch,
    ) {
        fun shutdown() = runCatching { runtime.shutdown() }

        /**
         * Simulates losing the session mid-transfer: never stop the client, just tear the runtime
         * down and wait until the session is actually over, so nothing keeps writing afterwards.
         */
        fun abortAfterSomeProgress() {
            // Give the transfer a moment to be genuinely partial, then kill it. If the tiny
            // loopback swarm finished before that, the scenario degrades to a clean restart -
            // which the assertions still cover.
            Thread.sleep(300)
            runCatching { runtime.shutdown() }
            done.await(30, TimeUnit.SECONDS)
        }
    }

    /**
     * Wraps a [Storage] and counts every byte read through it. Only the two abstract read/write
     * entry points are instrumented; the interface's *Fully defaults funnel through them, so no
     * read can slip past the counter. [locationOf] is delegated - the fingerprint gate must see
     * exactly where the engine really writes.
     */
    private class CountingStorage(private val delegate: Storage) : Storage {
        val bytesRead = AtomicLong()

        override fun getUnit(torrent: Torrent, torrentFile: TorrentFile): StorageUnit {
            val unit = delegate.getUnit(torrent, torrentFile)
            return object : StorageUnit {
                override fun readBlock(buffer: ByteBuffer, offset: Long): Int {
                    val read = unit.readBlock(buffer, offset)
                    if (read > 0) bytesRead.addAndGet(read.toLong())
                    return read
                }

                override fun writeBlock(buffer: ByteBuffer, offset: Long): Int = unit.writeBlock(buffer, offset)

                override fun writeBlock(buffer: ByteBufferView, offset: Long): Int = unit.writeBlock(buffer, offset)

                override fun capacity(): Long = unit.capacity()

                override fun size(): Long = unit.size()

                override fun close() = unit.close()
            }
        }

        override fun locationOf(torrent: Torrent, torrentFile: TorrentFile): Optional<Path> =
            delegate.locationOf(torrent, torrentFile)

        override fun flush() = delegate.flush()
    }

    private fun newFixture(): Fixture = Fixture(Files.createTempDirectory("blaze-fast-resume"))
}

// File-private extension: nested classes above all share it (a member extension of the test class
// would not resolve inside sibling nested classes).
private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
