package org.blaze.presentation.screens.downloads

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.presentation.screens.downloads.components.add.DestinationStatus
import org.blaze.presentation.screens.downloads.components.add.spaceShortBy
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the rules that decide when the downloads UI is allowed to speak.
 *
 * Every one of these predicates ends up as a warning, a confirmation dialog or a chip the user acts
 * on, so a wrong answer is not a cosmetic bug: warning about a source that resumes fine trains people
 * to ignore the warnings that matter. The tests below are therefore as much about *silence* - the
 * unproven, unknown case - as about the alerts themselves.
 */
class DownloadAdvisoryTest {

    private val mib = 1024L * 1024

    private fun download(
        id: String = "d1",
        url: String = "https://example.com/file.zip",
        state: DownloadState = DownloadState.DOWNLOADING,
        total: Long? = 10 * mib,
        done: Long = 4 * mib,
        speed: Long = 512 * 1024,
        peers: Int = 0,
        savePath: String = "/home/user/Downloads",
        resumeSupported: Boolean? = null
    ) = Download(
        id = id,
        name = url.substringAfterLast('/'),
        url = url,
        totalSize = total,
        downloadedSize = done,
        speed = speed,
        eta = null,
        peers = peers,
        state = state,
        addedAt = 0,
        savePath = savePath,
        resumeSupported = resumeSupported
    )

    // ------------------------------------------------------------------ capability is three-state

    @Test
    fun `an unproven source is unknown, never unsupported`() {
        // The engine only answers when a response proved it. Collapsing null into false would make
        // the app warn about lost progress on servers that resume perfectly well.
        assertEquals(ResumeSupport.UNKNOWN, download(resumeSupported = null).resumeSupport())
        assertEquals(ResumeSupport.SUPPORTED, download(resumeSupported = true).resumeSupport())
        assertEquals(ResumeSupport.UNSUPPORTED, download(resumeSupported = false).resumeSupport())
    }

    @Test
    fun `percent done is clamped to what the row shows`() {
        assertEquals(40, download().percentDone())
        assertEquals(100, download(done = 30 * mib).percentDone())
        assertEquals(0, download(done = 0).percentDone())
    }

    // ------------------------------------------------------------------ pausing

    @Test
    fun `pausing is only risky when bytes are at stake and the source cannot continue`() {
        assertTrue(download(state = DownloadState.DOWNLOADING, resumeSupported = false).pauseLosesProgress())
        assertTrue(download(state = DownloadState.QUEUED, resumeSupported = false).pauseLosesProgress())

        assertFalse(download(resumeSupported = true).pauseLosesProgress(), "a resumable source has nothing to lose")
        assertFalse(download(resumeSupported = null).pauseLosesProgress(), "unproven is not a reason to interrupt the user")
        assertFalse(download(done = 0, resumeSupported = false).pauseLosesProgress(), "nothing fetched, nothing discarded")
        assertFalse(
            download(state = DownloadState.PAUSED, resumeSupported = false).pauseLosesProgress(),
            "already paused: the damage, if any, is done"
        )
        assertFalse(
            download(state = DownloadState.COMPLETED, resumeSupported = false).pauseLosesProgress(),
            "a finished download cannot be paused"
        )
    }

    @Test
    fun `resuming a source that cannot continue is announced as starting over`() {
        assertTrue(download(state = DownloadState.PAUSED, resumeSupported = false).resumeStartsOver())
        assertTrue(download(state = DownloadState.FAILED, resumeSupported = false).resumeStartsOver())
        assertFalse(download(state = DownloadState.PAUSED, resumeSupported = null).resumeStartsOver())
        assertFalse(download(state = DownloadState.PAUSED, done = 0, resumeSupported = false).resumeStartsOver())
        assertFalse(download(state = DownloadState.DOWNLOADING, resumeSupported = false).resumeStartsOver())
    }

    @Test
    fun `an in-flight transfer on an unresumable source carries the chip`() {
        assertTrue(download(resumeSupported = false).isUnresumableInProgress())
        assertFalse(download(resumeSupported = true).isUnresumableInProgress())
        assertFalse(download(resumeSupported = null).isUnresumableInProgress())
        assertFalse(download(state = DownloadState.COMPLETED, resumeSupported = false).isUnresumableInProgress())
        assertFalse(download(state = DownloadState.FAILED, resumeSupported = false).isUnresumableInProgress())
    }

    // ------------------------------------------------------------------ torrents

    @Test
    fun `a torrent with no peers is stalled in a way no timeout will fix`() {
        assertTrue(
            download(url = "magnet:?xt=urn:btih:0123456789abc", peers = 0, speed = 0).isWaitingForPeers()
        )
        assertFalse(download(url = "magnet:?xt=urn:btih:0123456789abc", peers = 3, speed = 0).isWaitingForPeers())
        assertFalse(download(url = "magnet:?xt=urn:btih:0123456789abc", peers = 0, speed = 1024).isWaitingForPeers())
        assertFalse(
            download(state = DownloadState.QUEUED, url = "magnet:?xt=urn:btih:0123456789abc", peers = 0, speed = 0)
                .isWaitingForPeers(),
            "queued is not waiting for peers, it is waiting its turn"
        )
        assertFalse(download(peers = 0, speed = 0).isWaitingForPeers(), "an idle HTTP row is not a peer problem")
        assertTrue(download(url = "/home/user/debian.torrent", peers = 0, speed = 0).isWaitingForPeers())
    }

    @Test
    fun `a torrent is always told to resume, so the chip never fires for one`() {
        // The engine reports torrents as resumable from their piece bitmap; this pins that the UI
        // reads the capability rather than guessing from the scheme.
        assertFalse(
            download(url = "magnet:?xt=urn:btih:0123456789abc", resumeSupported = true).isUnresumableInProgress()
        )
    }

    // ------------------------------------------------------------------ status-bar tally

    @Test
    fun `attention counts group rows by reason`() {
        val rows = listOf(
            download(id = "a", resumeSupported = false),
            download(id = "b", resumeSupported = false),
            download(id = "c", url = "magnet:?xt=urn:btih:abc", peers = 0, speed = 0),
            download(id = "d", state = DownloadState.FAILED, resumeSupported = true),
            download(id = "e", state = DownloadState.COMPLETED, resumeSupported = true),
            download(id = "f", resumeSupported = null)
        )
        val attention = rows.attention()

        assertEquals(2, attention.cannotResume)
        assertEquals(1, attention.waitingForPeers)
        assertEquals(1, attention.failed)
        assertFalse(attention.isEmpty)
        assertTrue(listOf(download(id = "ok", resumeSupported = true)).attention().isEmpty)
    }

    // ------------------------------------------------------------------ lifecycle diffing

    @Test
    fun `the first snapshot never announces anything`() {
        // Startup restores a list full of completed and failed rows; notifying about all of them
        // would bury the user in balloons before they have done anything.
        val restored = listOf(download(id = "a", state = DownloadState.COMPLETED, done = 10 * mib))
        assertTrue(diffDownloadTransitions(emptyList(), restored).isEmpty())
    }

    @Test
    fun `finishing, failing and losing progress are each reported once`() {
        val before = listOf(
            download(id = "done", state = DownloadState.DOWNLOADING, done = 6 * mib),
            download(id = "broke", state = DownloadState.DOWNLOADING, done = 2 * mib),
            download(id = "lost", state = DownloadState.DOWNLOADING, done = 6 * mib)
        )
        val after = listOf(
            download(id = "done", state = DownloadState.COMPLETED, done = 10 * mib),
            download(id = "broke", state = DownloadState.FAILED, done = 2 * mib),
            // Bytes went backwards: the engine had to restart the transfer.
            download(id = "lost", state = DownloadState.DOWNLOADING, done = 0, total = 10 * mib)
        )

        val advisories = diffDownloadTransitions(before, after)

        assertEquals(3, advisories.size, advisories.toString())
        assertIs<DownloadAdvisory.Completed>(advisories[0]).let { assertEquals("done", it.download.id) }
        assertIs<DownloadAdvisory.Failed>(advisories[1]).let { assertEquals("broke", it.download.id) }
        val restarted = assertIs<DownloadAdvisory.Restarted>(advisories[2])
        assertEquals("lost", restarted.download.id)
        assertEquals(60, restarted.lostPercent, "the wording must name the percent that was on the row")
    }

    @Test
    fun `re-downloading a finished row whose file was deleted is not reported as lost progress`() {
        // The user asked for the file again; its counter dropping to 0 is the whole point of the
        // re-download, not a warning. The engine zeroes a re-queued finished task, so the queued
        // hop already carries 0 - the diff must not read the finished -> queued step as a restart.
        val completed = download(id = "again", state = DownloadState.COMPLETED, done = 10 * mib)
        val requeued = download(id = "again", state = DownloadState.QUEUED, done = 0)
        val moving = download(id = "again", state = DownloadState.DOWNLOADING, done = 0)

        assertTrue(
            diffDownloadTransitions(listOf(completed), listOf(requeued)).isEmpty(),
            "a completed -> queued re-download must not raise a 'discarding 100%' notice"
        )
        assertTrue(
            diffDownloadTransitions(listOf(requeued), listOf(moving)).isEmpty(),
            "starting the fresh transfer from 0 is not a loss either"
        )
    }

    @Test
    fun `an ordinary progress tick or a still-failed row is not news`() {
        val before = listOf(
            download(id = "steady", done = 4 * mib),
            download(id = "broke", state = DownloadState.FAILED, done = 1 * mib),
            download(id = "seeding", state = DownloadState.SEEDING, done = 10 * mib)
        )
        val after = listOf(
            download(id = "steady", done = 5 * mib),
            download(id = "broke", state = DownloadState.FAILED, done = 1 * mib),
            download(id = "seeding", state = DownloadState.SEEDING, done = 10 * mib)
        )

        assertTrue(diffDownloadTransitions(before, after).isEmpty(), "steady state must stay quiet")
    }

    @Test
    fun `rows that appear or disappear between snapshots are not announced`() {
        // Added elsewhere (or while this window was closed) and removed: neither is an event the user
        // witnessed, and the first case would also report every restored download as "completed".
        val onlyNew = listOf(download(id = "fresh", state = DownloadState.COMPLETED, done = 10 * mib))
        assertTrue(diffDownloadTransitions(onlyNew, emptyList()).isEmpty())
        assertTrue(diffDownloadTransitions(emptyList(), onlyNew).isEmpty())

        val unrelated = listOf(download(id = "other", done = 1 * mib))
        assertTrue(diffDownloadTransitions(unrelated, unrelated + onlyNew).isEmpty())
    }

    @Test
    fun `a restart that had already moved is never reported as a loss of zero percent`() {
        val before = listOf(download(id = "tiny", done = 1, total = 100 * mib))
        val after = listOf(download(id = "tiny", done = 0, total = 100 * mib))

        val restarted = assertIs<DownloadAdvisory.Restarted>(diffDownloadTransitions(before, after).single())
        assertEquals(1, restarted.lostPercent)
    }

    // ------------------------------------------------------------------ duplicate detection

    @Test
    fun `the same source into the same folder is recognised as a duplicate`() {
        val existing = listOf(download(id = "a", url = "https://example.com/f.zip", savePath = "/home/user/Downloads"))

        assertEquals("a", existing.findDuplicateOf("https://example.com/f.zip", "/home/user/Downloads")?.id)
        assertEquals(
            "a",
            existing.findDuplicateOf(" https://example.com/f.zip ", "/home/user/Downloads/")?.id,
            "a browser hands over a trailing slash and stray whitespace"
        )
        assertNull(existing.findDuplicateOf("https://example.com/f.zip", "/home/user/Desktop"))
        assertNull(existing.findDuplicateOf("https://example.com/other.zip", "/home/user/Downloads"))
        assertNull(existing.findDuplicateOf("", "/home/user/Downloads"))
        assertNull(emptyList<Download>().findDuplicateOf("https://example.com/f.zip", "/home/user/Downloads"))
    }

    // ------------------------------------------------------------------ destination space

    @Test
    fun `free space is only compared when both numbers are known`() {
        assertEquals(2 * mib, DestinationStatus.Ready(8 * mib).spaceShortBy(10 * mib))
        assertNull(DestinationStatus.Ready(10 * mib).spaceShortBy(10 * mib), "exactly enough is enough")
        assertNull(DestinationStatus.Ready(10 * mib).spaceShortBy(5 * mib))
        assertNull(DestinationStatus.Ready(10 * mib).spaceShortBy(null), "unknown size asks no question")
        assertNull(DestinationStatus.Ready(10 * mib).spaceShortBy(0), "a zero-length file fits anywhere")
        assertNull(DestinationStatus.Ready(-1).spaceShortBy(10 * mib), "an unreadable drive is not a shortage")
        assertNull(DestinationStatus.WillCreate.spaceShortBy(10 * mib), "the folder does not exist yet")
        assertNull(DestinationStatus.NotWritable.spaceShortBy(10 * mib), "writability is the bigger problem")
    }

    @Test
    fun `only a real problem with the folder blocks the add button`() {
        assertTrue(DestinationStatus.Invalid.isError)
        assertTrue(DestinationStatus.NotADirectory.isError)
        assertTrue(DestinationStatus.NotWritable.isError)
        assertFalse(DestinationStatus.Empty.isError, "an untouched field is not an error")
        assertFalse(DestinationStatus.WillCreate.isError)
        assertFalse(DestinationStatus.Ready(1).isError)
    }
}
