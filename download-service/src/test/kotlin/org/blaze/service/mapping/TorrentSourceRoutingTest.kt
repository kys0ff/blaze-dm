package org.blaze.service.mapping

import org.blaze.download.api.ApiErrorCode
import org.blaze.download.api.DownloadApiException
import org.blaze.download.api.DownloadSource
import org.blaze.download.api.NewDownloadRequest
import org.blaze.download.api.SourceKind
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState as EngineState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import java.time.Instant

/**
 * Locks the *routing decision* for the torrent family: what the service does with each shape of
 * torrent source, and in particular that an http(s) URL ending in `.torrent` becomes a torrent
 * whose metainfo is fetched over HTTP ([TorrentSource.Url]) instead of a doomed file download or
 * a garbage local path. This is the single place the local-vs-web distinction is made, so every
 * branch is tested here rather than replicated downstream.
 */
class TorrentSourceRoutingTest {

    private val dir = Path.of("/tmp/blaze-torrent-route")

    private fun map(
        source: DownloadSource,
        filename: String? = null,
        indices: List<Int>? = null,
    ): DownloadRequest = RequestMapper.toEngineRequest(
        NewDownloadRequest(source = source, destination = dir.toString(), filename = filename, selectedFileIndices = indices),
        dir,
    )

    @Test
    fun `an http URL ending in torrent becomes a URL-sourced torrent`() {
        val request = map(DownloadSource.TorrentFile("https://example.com/files/Big.Buck.torrent"))
        val torrent = assertIs<DownloadRequest.Torrent>(request)
        assertEquals(TorrentSource.Url("https://example.com/files/Big.Buck.torrent"), torrent.torrentSource)
        assertEquals("Big.Buck", torrent.name)
        assertEquals(dir.resolve("Big.Buck"), torrent.destination)
    }

    @Test
    fun `the URL display name is percent-decoded and cut at the query`() {
        val torrent = assertIs<DownloadRequest.Torrent>(
            map(DownloadSource.TorrentFile("https://x.dev/f/My%20Game.torrent?token=1"))
        )
        assertEquals("My Game", torrent.name)
    }

    @Test
    fun `scheme and suffix are matched case-insensitively`() {
        val torrent = assertIs<DownloadRequest.Torrent>(
            map(DownloadSource.TorrentFile("HTTPS://x.dev/Linux.TORRENT"))
        )
        assertIs<TorrentSource.Url>(torrent.torrentSource)
        assertEquals("Linux", torrent.name)
    }

    @Test
    fun `a URL segment with no usable name falls back like the GUI preview promises`() {
        val torrent = assertIs<DownloadRequest.Torrent>(map(DownloadSource.TorrentFile("https://x.dev/.torrent")))
        assertEquals("torrent-download", torrent.name)
    }

    @Test
    fun `an explicit filename wins over the URL segment`() {
        val torrent = assertIs<DownloadRequest.Torrent>(
            map(DownloadSource.TorrentFile("https://x.dev/a.torrent"), filename = "renamed")
        )
        assertEquals("renamed", torrent.name)
        assertEquals(dir.resolve("renamed"), torrent.destination)
    }

    @Test
    fun `selected file indices survive routing for URL torrents`() {
        val torrent = assertIs<DownloadRequest.Torrent>(
            map(DownloadSource.TorrentFile("https://x.dev/a.torrent"), indices = listOf(0, 2))
        )
        assertEquals(listOf(0, 2), torrent.fileIndices)
    }

    @Test
    fun `a local torrent path stays a file source`() {
        val torrent = assertIs<DownloadRequest.Torrent>(
            map(DownloadSource.TorrentFile("/home/user/My.Game.torrent"))
        )
        assertEquals(TorrentSource.File(Path.of("/home/user/My.Game.torrent")), torrent.torrentSource)
        assertEquals("My.Game", torrent.name)
    }

    @Test
    fun `a blank torrent string is rejected with INVALID_REQUEST`() {
        val ex = assertFailsWith<DownloadApiException> { map(DownloadSource.TorrentFile("   ")) }
        assertEquals(ApiErrorCode.INVALID_REQUEST, ex.error.code)
    }

    @Test
    fun `an unparseable local torrent path is rejected instead of crashing`() {
        val ex = assertFailsWith<DownloadApiException> { map(DownloadSource.TorrentFile("bad\u0000path.torrent")) }
        assertEquals(ApiErrorCode.INVALID_REQUEST, ex.error.code)
    }

    @Test
    fun `the dto tells the three torrent shapes apart`() {
        fun dtoOf(request: DownloadRequest) = DownloadTask(
            id = DownloadId("t-1"),
            name = request.name,
            request = request,
            state = EngineState.Queued,
            totalBytes = null,
            downloadedBytes = 0,
            downloadSpeed = 0,
            createdAt = Instant.now(),
        ).toDto()

        assertEquals(
            SourceKind.TORRENT_URL to "https://x.dev/a.torrent",
            dtoOf(DownloadRequest.Torrent("a", TorrentSource.Url("https://x.dev/a.torrent"), dir.resolve("a"))).let {
                it.source.kind to it.source.locator
            },
        )
        assertEquals(
            SourceKind.TORRENT_FILE to "a.torrent",
            dtoOf(DownloadRequest.Torrent("a", TorrentSource.File(Path.of("/tmp/a.torrent")), dir.resolve("a"))).let {
                it.source.kind to it.source.locator
            },
        )
        assertEquals(
            SourceKind.MAGNET to "magnet:?xt=urn:btih:abc",
            dtoOf(DownloadRequest.Torrent("a", TorrentSource.Magnet("magnet:?xt=urn:btih:abc"), dir.resolve("a"))).let {
                it.source.kind to it.source.locator
            },
        )
        // A magnet whose metainfo the engine cached on disk must still report the magnet, not the
        // internal cache path - this is the source the user sees and copies.
        assertEquals(
            SourceKind.MAGNET to "magnet:?xt=urn:btih:abc",
            dtoOf(
                DownloadRequest.Torrent(
                    "a",
                    TorrentSource.File(Path.of("/cache/abc.torrent"), TorrentSource.Magnet("magnet:?xt=urn:btih:abc")),
                    dir.resolve("a"),
                ),
            ).let { it.source.kind to it.source.locator },
        )
        assertEquals(
            SourceKind.TORRENT_URL to "https://x.dev/a.torrent",
            dtoOf(
                DownloadRequest.Torrent(
                    "a",
                    TorrentSource.File(Path.of("/cache/abc.torrent"), TorrentSource.Url("https://x.dev/a.torrent")),
                    dir.resolve("a"),
                ),
            ).let { it.source.kind to it.source.locator },
        )
    }
}
