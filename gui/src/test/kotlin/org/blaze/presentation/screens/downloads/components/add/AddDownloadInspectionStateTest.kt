package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.download.api.*
import kotlin.test.*

/**
 * The pure decision layer behind the Add-Download dialog's HLS and remote-ZIP flows.
 *
 * What is guarded here is the contract between a *validated inspection* and the typed request the
 * dialog eventually queues: routing stays type-driven (never filename-only), the acknowledgement a
 * DRM warning collects is carried but changes only reporting, and a selective archive submission
 * carries exactly the members the user ticked — with "nothing ticked" meaning the whole archive.
 * None of this needs Compose or Koin, so it is tested as plain state logic.
 */
class AddDownloadInspectionStateTest {

    // ────────────────────────────── pre-inspection heuristics ──────────────────────────────

    @Test
    fun `an m3u8-shaped http link is probed for HLS`() {
        assertTrue(isHlsPlaylistUrl("https://cdn.example.com/master.m3u8"))
        assertTrue(isHlsPlaylistUrl("https://example.com/index.m3u8?token=abc"))
        assertTrue(isHlsPlaylistUrl("https://example.com/STREAM.M3U8"))
        // Query must not hide the real path ending.
        assertFalse(isHlsPlaylistUrl("https://example.com/video.mp4"))
        assertFalse(isHlsPlaylistUrl("https://example.com/page.m3u8x"))
        // Only http(s) is probed; a local file or magnet is not an HLS stream link.
        assertFalse(isHlsPlaylistUrl("/local/master.m3u8"))
        assertFalse(isHlsPlaylistUrl("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun `a zip-shaped http link is probed for a remote archive`() {
        assertTrue(isRemoteArchiveUrl("https://example.com/archive.zip"))
        assertTrue(isRemoteArchiveUrl("https://example.com/a/b/Bundle.ZIP?dl=1"))
        assertFalse(isRemoteArchiveUrl("https://example.com/disk.iso"))
        assertFalse(isRemoteArchiveUrl("https://example.com/index.html"))
        assertFalse(isRemoteArchiveUrl("C:\\Downloads\\stuff.zip"))
    }

    // ─────────────────────────────── typed source routing ─────────────────────────────────

    @Test
    fun `a plain review queues no typed source`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            url = TextFieldValue("https://example.com/file.bin")
        }
        assertFalse(state.reviewedKind == AddDownloadState.ReviewedKind.HLS)
        assertNull(typedDownloadSource(state))
    }

    @Test
    fun `an HLS review carries the chosen variant and the DRM acknowledgement`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            url = TextFieldValue("https://cdn.example.com/master.m3u8")
            reviewedKind = AddDownloadState.ReviewedKind.HLS
            selectedHlsVariant = 2
            drmAcknowledged = true
        }

        val source = typedDownloadSource(state)
        assertTrue(source is DownloadSource.Hls)
        source
        assertEquals("https://cdn.example.com/master.m3u8", source.url)
        assertEquals(2, source.variantIndex)
        // The acknowledgement is what gates *reporting*; carrying it is expected, and it is the only
        // thing Continue changes about the request.
        assertTrue(source.drmAcknowledged)
    }

    @Test
    fun `an HLS review with no explicit pick lets the engine choose`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            url = TextFieldValue("https://cdn.example.com/master.m3u8")
            reviewedKind = AddDownloadState.ReviewedKind.HLS
        }

        val source = typedDownloadSource(state) as DownloadSource.Hls
        // -1 means "highest advertised bandwidth" — the engine decides, the dialog does not guess.
        assertEquals(-1, source.variantIndex)
        assertFalse(source.drmAcknowledged)
    }

    @Test
    fun `a selected archive members request carries exactly the ticks`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            url = TextFieldValue("https://example.com/bundle.zip")
            reviewedKind = AddDownloadState.ReviewedKind.ARCHIVE
            archiveInspection = browsable(selective = true)
            selectedArchiveEntries = setOf("docs/readme.md", "assets/logo.png")
        }

        val source = typedDownloadSource(state)
        assertTrue(source is DownloadSource.RemoteArchive)
        source
        // Order is normalised so a request is deterministic regardless of tick order.
        assertEquals(listOf("assets/logo.png", "docs/readme.md"), source.selectedEntries)
    }

    @Test
    fun `an empty archive selection means the whole archive`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            url = TextFieldValue("https://example.com/bundle.zip")
            reviewedKind = AddDownloadState.ReviewedKind.ARCHIVE
            archiveInspection = browsable(selective = true)
            selectedArchiveEntries = emptySet()
        }

        val source = typedDownloadSource(state) as DownloadSource.RemoteArchive
        // Nothing ticked is the whole-archive fallback, signalled by a null selection — never an
        // empty list that could be misread as "retrieve zero members".
        assertNull(source.selectedEntries)
    }

    // ──────────────────────────────── state properties ─────────────────────────────────────

    @Test
    fun `selection is only offered when ranges are served`() {
        val state = AddDownloadState("/home/user/Downloads")

        state.archiveInspection = browsable(selective = true)
        assertTrue(state.archiveCanSelect)

        state.archiveInspection = ArchiveInspectionDto.Browsable(
            entries = browsable(selective = true).entries,
            totalUncompressedBytes = 300,
            selectiveSupported = false,
        )
        assertFalse(state.archiveCanSelect)

        state.archiveInspection = ArchiveInspectionDto.FullDownloadOnly("host refuses ranges")
        assertFalse(state.archiveCanSelect)
    }

    @Test
    fun `archive byte estimate sums only the ticked files`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            archiveInspection = browsable(selective = true)
        }

        // Whole archive when nothing is picked (the two files sum to the total).
        state.selectedArchiveEntries = emptySet()
        assertEquals(300L, state.archiveSelectedBytes)

        // Both ticked files (200 + 100); the directory and the non-extractable member are excluded.
        state.selectedArchiveEntries = setOf("docs/readme.md", "assets/logo.png")
        assertEquals(300L, state.archiveSelectedBytes)

        // Only the smaller file.
        state.selectedArchiveEntries = setOf("assets/logo.png")
        assertEquals(100L, state.archiveSelectedBytes)
    }

    @Test
    fun `selective chosen requires a browsable archive and a real tick`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            archiveInspection = browsable(selective = true)
        }
        assertFalse(state.archiveSelectiveChosen)

        state.selectedArchiveEntries = setOf("docs/readme.md")
        assertTrue(state.archiveSelectiveChosen)

        // A non-browsable inspection never counts as a selective choice, ticks or not.
        state.archiveInspection = ArchiveInspectionDto.FullDownloadOnly("no ranges")
        assertFalse(state.archiveSelectiveChosen)
    }

    @Test
    fun `announced bytes route by reviewed kind`() {
        val state = AddDownloadState("/home/user/Downloads")

        state.reviewedKind = AddDownloadState.ReviewedKind.HLS
        state.hlsInspection = HlsInspectionDto(
            status = HlsInspectStatusDto.Playable,
            protection = HlsProtectionDto.None,
            estimatedTotalBytes = 4096L,
        )
        assertEquals(4096L, state.announcedBytes)

        state.reviewedKind = AddDownloadState.ReviewedKind.ARCHIVE
        state.archiveInspection = browsable(selective = true)
        state.selectedArchiveEntries = setOf("assets/logo.png")
        assertEquals(100L, state.announcedBytes)
    }

    @Test
    fun `editing the source clears every inspection verdict and choice`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            reviewedKind = AddDownloadState.ReviewedKind.HLS
            hlsInspection = HlsInspectionDto(HlsInspectStatusDto.Playable, HlsProtectionDto.None)
            selectedHlsVariant = 3
            drmAcknowledged = true
            showDrmWarning = true
            archiveInspection = browsable(selective = true)
            selectedArchiveEntries = setOf("docs/readme.md")
            archiveDownloadAllAcknowledged = true
            pendingArchiveFullDownloadReason = "ranges unavailable"
        }

        state.resetMetadata()

        assertEquals(AddDownloadState.ReviewedKind.PLAIN, state.reviewedKind)
        assertNull(state.hlsInspection)
        assertEquals(-1, state.selectedHlsVariant)
        assertFalse(state.drmAcknowledged)
        assertFalse(state.showDrmWarning)
        assertNull(state.archiveInspection)
        assertTrue(state.selectedArchiveEntries.isEmpty())
        assertFalse(state.archiveDownloadAllAcknowledged)
        assertNull(state.pendingArchiveFullDownloadReason)
    }

    // A small browsable fixture: a directory, two extractable files, one non-extractable member.
    private fun browsable(selective: Boolean) = ArchiveInspectionDto.Browsable(
        entries = listOf(
            ArchiveEntryDto("docs/", isDirectory = true, compressedSize = 0, uncompressedSize = 0, extractable = true),
            ArchiveEntryDto("docs/readme.md", false, 90, 200, extractable = true),
            ArchiveEntryDto("assets/logo.png", false, 80, 100, extractable = true),
            ArchiveEntryDto("secret.bin", false, 10, 10, extractable = false),
        ),
        totalUncompressedBytes = 300,
        selectiveSupported = selective,
    )
}
