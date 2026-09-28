package org.blaze.presentation.screens.downloads

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.data.resolvePreview
import org.blaze.data.sourceOf
import org.blaze.domain.repository.DownloadFile
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.download.api.DownloadSource
import org.blaze.presentation.screens.downloads.components.add.AddDownloadFetchOutcome
import org.blaze.presentation.screens.downloads.components.add.AddDownloadState
import org.blaze.presentation.screens.downloads.components.add.BatchDownloadItem
import org.blaze.presentation.screens.downloads.components.add.isLocalTorrentPath
import org.blaze.presentation.screens.downloads.components.add.isTorrentFamilySource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The torrent/magnet decision logic the add-download dialog runs on, tested without Compose:
 *
 * 1. source classification ([sourceOf], [isLocalTorrentPath], [isTorrentFamilySource]) - which
 *    family a pasted string belongs to;
 * 2. [AddDownloadFetchOutcome.of] - the probe answer → UX table (resolved / details-pending /
 *    rejected);
 * 3. [resolvePreview] naming, which must agree with what the service's RequestMapper delivers
 *    (the same expectations are asserted in TorrentSourceRoutingTest on the service side).
 */
class AddDownloadTorrentLogicTest {

    private val saveDir = "/home/user/Downloads"

    // ── classification ───────────────────────────────────────────────────────

    @Test
    fun `a web URL ending in torrent is a torrent source, not a plain file`() {
        assertIs<DownloadSource.TorrentFile>(sourceOf("https://example.com/get/linux.torrent"))
        assertIs<DownloadSource.TorrentFile>(sourceOf("  HTTP://x.dev/A.TORRENT "))
        assertIs<DownloadSource.TorrentFile>(sourceOf("https://x.dev/a.torrent?token=1"), "a query must not hide the ending")
    }

    @Test
    fun `magnets are matched case-insensitively and other URLs stay files`() {
        assertIs<DownloadSource.Magnet>(sourceOf("MAGNET:?xt=urn:btih:abc"))
        assertIs<DownloadSource.Url>(sourceOf("https://example.com/file.zip"))
        assertIs<DownloadSource.Url>(sourceOf("/home/user/document.pdf"))
    }

    @Test
    fun `only magnets and bare paths await metadata - a metainfo URL that stays silent is dead`() {
        val url = "https://example.com/linux.torrent"
        // It is still routed to the torrent family downstream (the service maps it to a URL
        // torrent), but the GUI never treats a silent metainfo server as "pending": unlike a
        // cold swarm, a metainfo host that ignores a 30s probe is a link worth refusing.
        assertFalse(isTorrentFamilySource(url))
        assertFalse(isLocalTorrentPath(url))
        assertTrue(isLocalTorrentPath("/home/user/linux.TORRENT"))
        assertFalse(isLocalTorrentPath("ftp://example.com/linux.torrent"), "any :// makes it a URL")
        assertFalse(isLocalTorrentPath("magnet:?xt=urn:btih:abc"))
        assertTrue(isTorrentFamilySource("magnet:?xt=urn:btih:abc"))
        assertTrue(isTorrentFamilySource("/home/user/linux.torrent"))
        assertFalse(isTorrentFamilySource("https://example.com/file.zip"))
    }

    // ── probe outcome decision table ─────────────────────────────────────────

    @Test
    fun `a resolved probe always yields the normal review`() {
        val metadata = DownloadMetadata("Big.Buck", 100L)
        val outcome = AddDownloadFetchOutcome.of("magnet:?xt=urn:btih:abc", metadata, probeError = null)
        assertEquals(AddDownloadFetchOutcome.Resolved(metadata), outcome)
    }

    @Test
    fun `a silent torrent probe is details-pending, not a failure`() {
        for (source in listOf("magnet:?xt=urn:btih:abc", "/home/user/big.torrent")) {
            val outcome = AddDownloadFetchOutcome.of(source, metadata = null, probeError = null)
            assertIs<AddDownloadFetchOutcome.Unresolved>(outcome, source)
        }
    }

    @Test
    fun `a throwing probe is rejected even for a torrent - awaiting metadata never raises`() {
        val magnet = "magnet:?xt=urn:btih:abc"
        val outcome = AddDownloadFetchOutcome.of(magnet, metadata = null, probeError = "boom")
        assertEquals("boom", assertIs<AddDownloadFetchOutcome.Rejected>(outcome).reason)
    }

    @Test
    fun `a dead http link is rejected with the link as the reason`() {
        for (link in listOf("https://dead.example/x", "https://dead.example/linux.torrent")) {
            val outcome = AddDownloadFetchOutcome.of(link, metadata = null, probeError = null)
            assertEquals(link, assertIs<AddDownloadFetchOutcome.Rejected>(outcome).reason)
        }
    }

    // ── preview naming, which must match the service's eventual name ─────────

    @Test
    fun `preview of a torrent URL mirrors the service name`() {
        assertEquals(
            Path.of("$saveDir/Big.Buck"),
            resolvePreview("https://example.com/files/Big.Buck.torrent", saveDir, name = null),
        )
        assertEquals(
            Path.of("$saveDir/My Game"),
            resolvePreview("https://x.dev/f/My%20Game.torrent?token=1", saveDir, name = null),
        )
        assertEquals(
            Path.of("$saveDir/torrent-download"),
            resolvePreview("https://x.dev/.torrent", saveDir, name = null),
        )
    }

    @Test
    fun `preview of a local torrent and a magnet mirror the service name`() {
        assertEquals(
            Path.of("$saveDir/My.Game"),
            resolvePreview("/home/user/My.Game.TORRENT", saveDir, name = null),
        )
        assertEquals(
            Path.of("$saveDir/Ubuntu 24.04"),
            resolvePreview("magnet:?xt=urn:btih:abc&dn=Ubuntu%2024.04", saveDir, name = null),
        )
        assertEquals(
            Path.of("$saveDir/torrent-download"),
            resolvePreview("magnet:?xt=urn:btih:abc", saveDir, name = null),
        )
    }

    @Test
    fun `an explicit name overrides every preview rule`() {
        assertEquals(
            Path.of("$saveDir/renamed"),
            resolvePreview("https://example.com/files/Big.Buck.torrent", saveDir, name = "renamed"),
        )
    }

    // ── dialog state ─────────────────────────────────────────────────────────

    @Test
    fun `the fetch button follows the classification`() {
        AddDownloadState(saveDir).apply {
            url = TextFieldValue("magnet:?xt=urn:btih:abc")
            assertTrue(canFetch)
            url = TextFieldValue("https://example.com/linux.torrent")
            assertTrue(canFetch)
            url = TextFieldValue("ftp://example.com/linux.torrent")
            assertFalse(canFetch)
            url = TextFieldValue("")
            assertFalse(canFetch)
            assertTrue(looksSupported, "an empty source must not nag before the user typed anything")
        }
    }

    @Test
    fun `resetting metadata clears the details-pending flag too`() {
        AddDownloadState(saveDir).apply {
            metadataUnresolved = true
            resetMetadata()
            assertFalse(metadataUnresolved)
        }
    }

    @Test
    fun `a batch torrent item starts with every file selected`() {
        val files = listOf(
            DownloadFile("a.bin", 10L, index = 0),
            DownloadFile("b.bin", 20L, index = 1),
            DownloadFile("c.bin", 30L, index = 2),
        )
        assertEquals(setOf(0, 1, 2), BatchDownloadItem("magnet:?xt=urn:btih:abc", DownloadMetadata("m", 60L, files)).selectedFileIndices)
        assertEquals(emptySet(), BatchDownloadItem("https://x/a.zip").selectedFileIndices)
        assertEquals(emptySet(), BatchDownloadItem("m", DownloadMetadata("m", null)).selectedFileIndices)
    }

    private inline fun <reified T : Any> assertIs(value: Any?, clue: String = ""): T {
        assertTrue(value is T, "expected ${T::class.simpleName} but was ${value?.let { it::class.simpleName }} $clue")
        return value
    }
}
