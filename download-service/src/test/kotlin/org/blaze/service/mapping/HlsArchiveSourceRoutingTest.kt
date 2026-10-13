package org.blaze.service.mapping

import org.blaze.download.api.*
import org.blaze.engine.api.*
import java.nio.file.Path
import kotlin.test.*

/**
 * Locks the *routing decision* for the two new handler families, mirroring [TorrentSourceRoutingTest]:
 * a declared HLS or remote-archive source reaches the matching engine request, its name is derived the way
 * the preview promises, a non-http(s) scheme is rejected with a stable code, and credentials ride only on
 * the in-memory request. It also pins the engine->DTO projection so the Add Download dialog always sees a
 * total verdict rather than a silently-collapsed status.
 */
class HlsArchiveSourceRoutingTest {

    private val dir = Path.of("/tmp/blaze-hls-archive-route")

    private fun map(
        source: DownloadSource,
        filename: String? = null,
        credentials: Map<String, String> = emptyMap(),
    ): DownloadRequest = RequestMapper.toEngineRequest(
        NewDownloadRequest(
            source = source,
            destination = dir.toString(),
            filename = filename,
            credentials = credentials
        ),
        dir,
    )

    @Test
    fun `a declared HLS source becomes an Hls request carrying variant and DRM choice`() {
        val request =
            map(DownloadSource.Hls("https://host/streams/master.m3u8", variantIndex = 3, drmAcknowledged = true))
        val hls = assertIs<DownloadRequest.Hls>(request)
        assertEquals("https://host/streams/master.m3u8", hls.playlistUrl)
        assertEquals(3, hls.variantIndex)
        assertTrue(hls.drmAcknowledged, "the acknowledged choice must reach the engine")
        assertEquals("master.ts", hls.name, "the assembled stream carries a .ts container extension")
        assertEquals(dir.resolve("master.ts"), hls.destination)
    }

    @Test
    fun `a numeric playlist URL with no filename extension still yields a container extension`() {
        // The reported case: a bitratexresolution segment that leaves an extension-less name once the
        // .m3u8 is trimmed - the output must be playable, so Blaze appends the MPEG-TS container suffix.
        val hls = assertIs<DownloadRequest.Hls>(
            map(
                DownloadSource.Hls(
                    "https://videoar.cgtn.com/public/2018-03-22/video/x/1096000-1280x720/1096000-1280x720.m3u8"
                )
            )
        )
        assertEquals("1096000-1280x720.ts", hls.name)
        assertEquals(dir.resolve("1096000-1280x720.ts"), hls.destination)
    }

    @Test
    fun `an explicit filename wins over the playlist segment`() {
        val hls = assertIs<DownloadRequest.Hls>(map(DownloadSource.Hls("https://x/a.m3u8"), filename = "episode"))
        assertEquals("episode", hls.name)
        assertEquals(dir.resolve("episode"), hls.destination)
    }

    @Test
    fun `hls credentials travel on the request only`() {
        val hls = assertIs<DownloadRequest.Hls>(
            map(DownloadSource.Hls("https://x/a.m3u8"), credentials = mapOf("Authorization" to "Bearer t"))
        )
        assertEquals(mapOf("Authorization" to "Bearer t"), hls.headers)
    }

    @Test
    fun `a non-http scheme is rejected for HLS`() {
        val ex = assertFailsWith<DownloadApiException> { map(DownloadSource.Hls("ftp://host/a.m3u8")) }
        assertEquals(ApiErrorCode.INVALID_URL, ex.error.code)
    }

    @Test
    fun `a declared archive source becomes an Archive request with its selection`() {
        val request =
            map(DownloadSource.RemoteArchive("https://host/bundle.zip", selectedEntries = listOf("a.txt", "b/c.txt")))
        val archive = assertIs<DownloadRequest.Archive>(request)
        assertEquals("https://host/bundle.zip", archive.url)
        assertEquals("bundle.zip", archive.name)
        assertEquals(listOf("a.txt", "b/c.txt"), archive.selectedEntries)
        assertEquals(dir.resolve("bundle.zip"), archive.destination)
    }

    @Test
    fun `an archive with no selection requests a full download`() {
        val archive = assertIs<DownloadRequest.Archive>(map(DownloadSource.RemoteArchive("https://host/b.zip")))
        assertNull(archive.selectedEntries, "null selection means download the whole archive")
    }

    @Test
    fun `a non-http scheme is rejected for archives`() {
        val ex = assertFailsWith<DownloadApiException> { map(DownloadSource.RemoteArchive("file:///etc/passwd.zip")) }
        assertEquals(ApiErrorCode.INVALID_URL, ex.error.code)
    }

    // ------------------------------------------------------------------ inspection projection

    @Test
    fun `the hls verdict projects onto the transport dto without loss`() {
        val inspection = HlsInspection(
            status = HlsInspectStatus.NeedsDrmConsent,
            protection = HlsProtectionLevel.DrmSuspected,
            variants = listOf(
                HlsVariantInfo(
                    index = 0,
                    bandwidth = 5_000_000,
                    resolution = "1920x1080",
                    codecs = "avc1",
                    language = null,
                    name = "1080p"
                )
            ),
            selectedVariantIndex = 0,
            estimatedTotalBytes = 123_456L,
            message = "may be protected",
        )
        val dto = inspection.toDto()
        assertEquals(HlsInspectStatusDto.NeedsDrmConsent, dto.status)
        assertEquals(HlsProtectionDto.DrmSuspected, dto.protection)
        assertEquals(1, dto.variants.size)
        assertEquals("1080p", dto.variants.first().name)
        assertEquals(5_000_000L, dto.variants.first().bandwidth)
        assertEquals(123_456L, dto.estimatedTotalBytes)
    }

    @Test
    fun `archive inspection projects browsable, full-only and unavailable faithfully`() {
        val browsable = ArchiveInspection.Browsable(
            entries = listOf(ArchiveEntryInfo("docs/a.txt", false, 10, 100, extractable = true)),
            totalUncompressedBytes = 100,
            selectiveSupported = true,
            reasonIfUnsupported = null,
        ).toDto()
        assertIs<ArchiveInspectionDto.Browsable>(browsable)
        assertTrue(browsable.selectiveSupported)
        assertEquals("docs/a.txt", browsable.entries.single().name)

        assertIs<ArchiveInspectionDto.FullDownloadOnly>(ArchiveInspection.FullDownloadOnly("no ranges").toDto())
        assertIs<ArchiveInspectionDto.Unavailable>(ArchiveInspection.Unavailable("not a zip").toDto())
    }
}
