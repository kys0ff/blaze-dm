package org.blaze.osintegration

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The startup-safe queue: producers (argv, macOS events, IPC forwards) and consumers (the GUI dialog
 * pump) never have to be alive at the same time, so the inbox contract is exercised directly.
 */
class ExternalOpenInboxTest {

    private val torrentA = ExternalOpenRequest.TorrentFile(Paths.get("/tmp/a.torrent"))
    private val torrentB = ExternalOpenRequest.TorrentFile(Paths.get("/tmp/b.torrent"))
    private val magnet = ExternalOpenRequest.MagnetUri("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")

    @Test
    fun `requests submitted before any consumer exists are still queued`() {
        // The cold-start race in one test: the launcher submits during startup; the UI "subscribes" later.
        val inbox = ExternalOpenInbox()
        inbox.submit(torrentA)
        inbox.submit(listOf(magnet, torrentB))
        assertEquals(listOf(torrentA, magnet, torrentB), inbox.pending.value)
    }

    @Test
    fun `an identical request already pending is collapsed, not queued twice`() {
        val inbox = ExternalOpenInbox()
        inbox.submit(torrentA)
        inbox.submit(torrentA) // double activation of the file association
        assertEquals(listOf(torrentA), inbox.pending.value)
    }

    @Test
    fun `ack removes exactly one occurrence and frees the slot for a future identical open`() {
        val inbox = ExternalOpenInbox()
        inbox.submit(torrentA)
        inbox.ack(torrentA)
        assertEquals(emptyList(), inbox.pending.value)
        inbox.ack(torrentA) // acking something unknown is harmless
        assertEquals(emptyList(), inbox.pending.value)
        inbox.submit(torrentA) // re-opened after handling: a fresh request again
        assertEquals(listOf(torrentA), inbox.pending.value)
    }

    @Test
    fun `bring-to-front signals are monotonic and independent of the queue`() {
        val inbox = ExternalOpenInbox()
        assertEquals(0L, inbox.bringToFrontSignal.value)
        inbox.notifyBringToFront()
        inbox.notifyBringToFront()
        assertEquals(2L, inbox.bringToFrontSignal.value)
        assertTrue(inbox.pending.value.isEmpty())
    }
}
