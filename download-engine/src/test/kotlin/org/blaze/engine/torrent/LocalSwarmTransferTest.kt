package org.blaze.engine.torrent

import bt.Bt
import bt.data.file.FileSystemStorage
import bt.net.InetPeer
import bt.runtime.BtRuntime
import bt.torrent.maker.TorrentBuilder
import org.blaze.engine.support.SwarmHarness
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Closes TOR-8: the first test that proves a *real* torrent transfer completes end to end.
 *
 * A seeder and a leecher run in two separate `BtRuntime`s on loopback (each with its own acceptor
 * port — one runtime can never see an in-process peer, see [SwarmHarness]). The leecher is handed
 * the seeder's socket through a static peer source: no tracker, no DHT, no multicast, everything on
 * 127.0.0.1. The payload is deliberately a *multi-file* torrent of a few MiB with sizes that do not
 * align with the piece size, so pieces span file boundaries and every file is re-verified here by
 * SHA-256 — a check independent of the SHA-1 piece hashes bt uses on the wire.
 */
class LocalSwarmTransferTest {

    @Test
    fun `leecher completes a multi-file transfer from a local seeder and every file matches sha256`() {
        val root = Files.createTempDirectory("blaze-swarm-transfer")
        val seedDir = Files.createDirectories(root.resolve("seed"))
        val bundleDir = Files.createDirectories(seedDir.resolve("bundle"))
        val leechDir = Files.createDirectories(root.resolve("leech"))

        // ~3.6 MiB across three files; only beta.bin is piece-aligned.
        val sizes = linkedMapOf(
            "alpha.bin" to 1024 * 1024 + 12_345,
            "beta.bin" to 2 * 1024 * 1024,
            "gamma.bin" to 512 * 1024 + 9_999
        )
        val random = Random(0xB1A2E)
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
        val torrentFile = root.resolve("swarm.torrent")
        Files.write(torrentFile, torrentBytes)
        val torrentUrl = torrentFile.toUri().toURL()

        val seederPort = SwarmHarness.reserveFreePort()
        val leecherPort = SwarmHarness.reserveFreePort()
        val seederRuntime: BtRuntime = SwarmHarness.seederRuntime(seederPort)
        val leecherRuntime: BtRuntime = SwarmHarness.leecherRuntime(
            leecherPort,
            listOf(InetPeer.build(SwarmHarness.loopback(), seederPort))
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
            leecher.startAsync().get(90, TimeUnit.SECONDS)

            sizes.forEach { (name, size) ->
                val downloaded = leechDir.resolve("bundle").resolve(name)
                assertEquals(size.toLong(), Files.size(downloaded), "size of downloaded $name")
                assertEquals(
                    expectedSha256.getValue(name),
                    Files.readAllBytes(downloaded).sha256Hex(),
                    "SHA-256 of downloaded $name"
                )
            }
        } finally {
            runCatching { leecherRuntime.shutdown() }
            runCatching { seederRuntime.shutdown() }
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
