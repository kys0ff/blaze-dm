package org.blaze.engine.torrent

import bt.metainfo.MetadataService
import bt.metainfo.Torrent
import bt.torrent.maker.TorrentBuilder
import org.blaze.engine.network.webseed.HttpWebSeedFetcher
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the BEP-19 URL-building contract of [HttpWebSeedFetcher] in isolation: a seed URL is a
 * directory root, a multi-file torrent nests under a folder named after the torrent, and every path
 * element is percent-encoded. The end-to-end shape is proven in [WebSeedTransferTest]; these are the
 * string-level edge cases (trailing slash, spaces) that would otherwise be awkward to see through a
 * real transfer.
 */
class HttpWebSeedFetcherTest {

    private fun multiFileTorrent(): Torrent {
        val dir = Files.createTempDirectory("blaze-webseed-url")
        val bundle = Files.createDirectories(dir.resolve("bundle"))
        Files.write(bundle.resolve("alpha.bin"), byteArrayOf(1, 2, 3))
        Files.write(bundle.resolve("beta.bin"), byteArrayOf(4, 5, 6))
        val bytes = TorrentBuilder()
            .rootPath(bundle)
            .addFiles(bundle.resolve("alpha.bin"), bundle.resolve("beta.bin"))
            .pieceSize(16 * 1024)
            .build()
        return MetadataService().fromByteArray(bytes)
    }

    @Test
    fun `a multi-file seed nests the torrent name then the file path`() {
        val torrent = multiFileTorrent()
        assertEquals(
            "http://host/seed/bundle/alpha.bin",
            HttpWebSeedFetcher.buildFileUrl(torrent, "http://host/seed/", listOf("alpha.bin")),
        )
    }

    @Test
    fun `a seed url without a trailing slash still joins with exactly one separator`() {
        val torrent = multiFileTorrent()
        assertEquals(
            "http://host/seed/bundle/alpha.bin",
            HttpWebSeedFetcher.buildFileUrl(torrent, "http://host/seed", listOf("alpha.bin")),
        )
    }

    @Test
    fun `path elements are percent-encoded and directory separators are preserved`() {
        val torrent = multiFileTorrent()
        assertEquals(
            "http://host/seed/bundle/sub%20dir/a%20b.bin",
            HttpWebSeedFetcher.buildFileUrl(torrent, "http://host/seed", listOf("sub dir", "a b.bin")),
        )
    }
}
