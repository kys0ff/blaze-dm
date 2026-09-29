package org.blaze.engine.storage

import bt.Bt
import bt.runtime.BtRuntime
import bt.runtime.Config
import bt.torrent.maker.TorrentBuilder
import bt.tracker.http.HttpTrackerModule
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks Blaze's own torrent storage against the real library, not against a stub of it.
 *
 * [TorrentContentLayoutTest] pins the path arithmetic, but the assumption that actually matters -
 * that a multi-file torrent's path elements are relative to the folder named in the metainfo, so the
 * library must not be allowed to add that folder a second time - can only be checked by `bt` itself.
 *
 * The session below needs no peers and no network. Its content is copied into place in the layout
 * Blaze produces, then `bt` is asked to run the torrent to completion: if every unit resolves to a
 * real file, all pieces verify on the first pass and the client finishes. If the layout were wrong,
 * `bt` would see holes, wait for pieces from a swarm that does not exist, and the future would time
 * out - which is precisely how the doubled `Big.Buck/Big.Buck/…` folder shows up as a failure here.
 */
class TorrentContentStorageSessionTest {

    @Test
    fun `bt accepts multi-file content laid out flat by blaze storage`() {
        val root = Files.createTempDirectory("blaze-storage-session")
        val content = root.resolve("src/Big.Buck")
        Files.createDirectories(content.resolve("subs"))
        // Big enough to span several pieces, small enough to hash instantly.
        Files.write(content.resolve("bunny.mp4"), ByteArray(128 * 1024) { (it % 251).toByte() })
        Files.write(content.resolve("subs/en.srt"), "00:01,0 --> 00:04,0\nhello world\n".toByteArray())

        val torrentFile = root.resolve("sample.torrent")
        Files.write(
            torrentFile,
            TorrentBuilder()
                .rootPath(content)
                .addFile(content)
                .announce(DEAD_TRACKER)
                .pieceSize(64 * 1024)
                .build()
        )

        // The download's own folder, holding the content exactly as the metainfo describes it.
        val destination = root.resolve("downloads/Big.Buck")
        Files.createDirectories(destination.resolve("subs"))
        Files.copy(content.resolve("bunny.mp4"), destination.resolve("bunny.mp4"))
        Files.copy(content.resolve("subs/en.srt"), destination.resolve("subs/en.srt"))

        val config = Config().apply {
            acceptorAddress = InetAddress.getLoopbackAddress()
            maxPeerConnections = 2
        }
        val runtime = BtRuntime.builder(config)
            .module(HttpTrackerModule())
            .disableAutomaticShutdown()
            .build()
        try {
            val finished = runCatching {
                Bt.client(runtime)
                    .storage(TorrentContentStorage(destination))
                    .torrent(torrentFile.toUri().toURL())
                    .stopWhenDownloaded()
                    .build()
                    .startAsync()
                    .get(90, TimeUnit.SECONDS)
            }.isSuccess

            assertTrue(finished, "bt did not find the content where Blaze's storage puts it")
            assertFalse(
                Files.exists(destination.resolve("Big.Buck")),
                "the torrent name must not reappear as a folder inside its own download"
            )
        } finally {
            runCatching { runtime.shutdown() }
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        /** Nothing listens here: discovery is irrelevant, and the session must not reach the network. */
        const val DEAD_TRACKER = "http://127.0.0.1:1/announce"
    }
}
