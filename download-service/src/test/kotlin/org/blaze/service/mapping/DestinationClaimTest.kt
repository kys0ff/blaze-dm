package org.blaze.service.mapping

import org.blaze.download.api.DownloadSource
import org.blaze.download.api.NewDownloadRequest
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.settings.FileConflictBehavior
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The moment a download is created is the only moment a folder that already exists still proves a
 * collision: a torrent starts writing into its own folder immediately, and from then on existence
 * describes the download itself. So the step aside happens here, once, on a name nobody has claimed
 * yet - which is also why an HTTP request, whose collision is settled at transfer time next to its
 * `.part` file, is deliberately left alone.
 */
class DestinationClaimTest {

    private val dir = Path.of("/tmp/blaze-claim")

    // TODO: Warning: Value of parameter 'name' is always '"Big.Buck"'
    private fun torrent(name: String, destination: Path) = DownloadRequest.Torrent(
        name = name,
        torrentSource = TorrentSource.File(Path.of("/tmp/sources/$name.torrent")),
        destination = destination,
    )

    private fun claim(request: DownloadRequest, behavior: FileConflictBehavior, vararg taken: Path): DownloadRequest =
        RequestMapper.claimDestination(request, behavior) { it in taken.toList() }

    @Test
    fun `a taken folder becomes a numbered copy, and the name follows it`() {
        val claimed = claim(
            torrent("Big.Buck", dir.resolve("Big.Buck")),
            FileConflictBehavior.RENAME,
            taken = arrayOf(dir.resolve("Big.Buck")),
        )

        val torrent = claimed as DownloadRequest.Torrent
        assertEquals(dir.resolve("Big.Buck (1)"), torrent.destination)
        assertEquals("Big.Buck (1)", torrent.name)
        assertEquals(torrent.destination.parent, dir)
    }

    @Test
    fun `asking is the same as renaming, because whoever asked was just offered the alternative`() {
        val taken = dir.resolve("Big.Buck")

        val claimed = claim(torrent("Big.Buck", taken), FileConflictBehavior.ASK, taken = arrayOf(taken))

        assertEquals(dir.resolve("Big.Buck (1)"), claimed.destination)
    }

    @Test
    fun `the first free number is taken, not the first number`() {
        val claimed = claim(
            torrent("Big.Buck", dir.resolve("Big.Buck")),
            FileConflictBehavior.RENAME,
            taken = arrayOf(dir.resolve("Big.Buck"), dir.resolve("Big.Buck (1)"), dir.resolve("Big.Buck (2)")),
        )

        assertEquals(dir.resolve("Big.Buck (3)"), claimed.destination)
    }

    @Test
    fun `a folder that is free is the folder the download keeps`() {
        val request = torrent("Big.Buck", dir.resolve("Big.Buck"))

        assertEquals(request, claim(request, FileConflictBehavior.RENAME, taken = arrayOf(dir.resolve("Something.Else"))))
    }

    @Test
    fun `overwriting and skipping leave the destination to the policy that will act on it`() {
        val taken = dir.resolve("Big.Buck")
        val request = torrent("Big.Buck", taken)

        assertEquals(request, claim(request, FileConflictBehavior.OVERWRITE, taken = arrayOf(taken)))
        assertEquals(request, claim(request, FileConflictBehavior.SKIP, taken = arrayOf(taken)))
    }

    @Test
    fun `an http download is not claimed, because its collision happens later`() {
        val http = RequestMapper.toEngineRequest(
            NewDownloadRequest(
                source = DownloadSource.Url("https://example.com/movie.mkv"),
                destination = dir.toString(),
            ),
            dir,
        )

        assertEquals(http, claim(http, FileConflictBehavior.RENAME, taken = arrayOf(dir.resolve("movie.mkv"))))
    }
}
