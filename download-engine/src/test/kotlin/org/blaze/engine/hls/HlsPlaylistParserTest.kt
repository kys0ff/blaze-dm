package org.blaze.engine.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Offline coverage for the HLS playlist parser: every branch that decides *what to download* and
 * *whether it is protected* is exercised against fixed playlist text, so no test ever leans on a
 * live streaming host.
 */
class HlsPlaylistParserTest {

    private val base = "https://cdn.example.com/video/master.m3u8"

    @Test
    fun `a vod media playlist parses its segments and resolves relative urls`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:9.009,
            seg-0.ts
            #EXTINF:9.009,
            sub/seg-1.ts
            #EXTINF:3.0,
            https://other.example.com/seg-2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val p = HlsPlaylistParser.parse(text, base)
        assertEquals(HlsPlaylistKind.Media, p.kind)
        assertEquals(3, p.segments.size)
        assertEquals(10.0, p.targetDurationSeconds)
        assertTrue(p.endList)
        assertEquals("https://cdn.example.com/video/seg-0.ts", p.segments[0].uri)
        assertEquals("https://cdn.example.com/video/sub/seg-1.ts", p.segments[1].uri)
        assertEquals("https://other.example.com/seg-2.ts", p.segments[2].uri)
        assertEquals(HlsProtection.None, p.protection)
        assertTrue(!p.isLive)
    }

    @Test
    fun `a live playlist without endlist is flagged as live`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            chunk.ts
        """.trimIndent()
        val p = HlsPlaylistParser.parse(text, base)
        assertTrue(p.isLive)
        assertTrue(!p.endList)
    }

    @Test
    fun `a master playlist yields variants with metadata`() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=720x480,CODECS="avc1.4d401f"
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720
            high.m3u8
        """.trimIndent()
        val p = HlsPlaylistParser.parse(text, base)
        assertEquals(HlsPlaylistKind.Master, p.kind)
        assertEquals(2, p.variants.size)
        assertEquals(1280000L, p.variants[0].bandwidth)
        assertEquals("720x480", p.variants[0].resolution)
        assertEquals("avc1.4d401f", p.variants[0].codecs)
        assertEquals("https://cdn.example.com/video/high.m3u8", p.variants[1].uri)
    }

    @Test
    fun `aes-128 is ordinary encryption, not drm`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x00000000000000000000000000000001
            #EXTINF:10.0,
            seg-0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylistParser.parse(text, base)
        assertEquals(HlsKeyMethod.Aes128, p.keyMethod)
        assertEquals(HlsProtection.OrdinaryEncryption, p.protection)
        assertEquals("https://cdn.example.com/video/key.bin", p.segments[0].keyUri)
        assertEquals("00000000000000000000000000000001", p.segments[0].iv)
    }

    @Test
    fun `sample-aes is a drm indication`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key"
            #EXTINF:10.0,
            seg-0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylistParser.parse(text, base)
        assertEquals(HlsProtection.DrmSuspected, p.protection)
        assertTrue(p.drmIndicators.isNotEmpty())
    }

    @Test
    fun `a fairplay session key is a drm indication`() {
        val text = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.apple.streamingkeydelivery",URI="skd://"
            #EXT-X-TARGETDURATION:10
            #EXT-X-KEY:METHOD=AES-128,URI="k.bin"
            #EXTINF:10.0,
            seg-0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylistParser.parse(text, base)
        assertEquals(HlsProtection.DrmSuspected, p.protection)
    }

    @Test
    fun `an unrecognised key method is uncertain, not drm`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-KEY:METHOD=SOMETHING-ELSE,URI="k.bin"
            #EXTINF:10.0,
            seg-0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylistParser.parse(text, base)
        assertEquals(HlsKeyMethod.Unknown, p.keyMethod)
        assertEquals(HlsProtection.Uncertain, p.protection)
    }

    @Test
    fun `a body without the extm3u magic is rejected`() {
        assertFailsWith<HlsPlaylistException> {
            HlsPlaylistParser.parse("<html><body>not a playlist</body></html>", base)
        }
    }

    @Test
    fun `a playlist with neither segments nor variants is rejected`() {
        assertFailsWith<HlsPlaylistException> {
            HlsPlaylistParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-ENDLIST\n", base)
        }
    }

    @Test
    fun `attribute list parsing keeps commas inside quoted values`() {
        val map = HlsPlaylistParser.parseAttributeList(
            "BANDWIDTH=1000,CODECS=\"avc1.4d400d,mp4a.40.2\",NAME=\"High, 720p\""
        )
        assertEquals("1000", map["BANDWIDTH"])
        assertEquals("avc1.4d400d,mp4a.40.2", map["CODECS"])
        assertEquals("High, 720p", map["NAME"])
    }
}
