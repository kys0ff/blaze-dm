package org.blaze.osintegration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The widened classification contract used by in-app surfaces (window drag-and-drop, clipboard
 * monitoring): plain `http(s)` links are accepted *there* while the OS launch path keeps refusing
 * them. The file probe is stubbed so torrent-path branches stay deterministic.
 */
class ExternalOpenDropClassificationTest {

    private fun parserAcceptingAllFiles(): ExternalOpenParser = ExternalOpenParser { PathProbe.REGULAR_FILE }

    private fun classifyReason(parser: ExternalOpenParser, raw: String): RejectionReason? =
        parser.classifyForDrop(raw).exceptionOrNull()?.let { e ->
            RejectionReason.entries.first { it.name == e.message }
        }

    @Test
    fun `web links become WebLink requests kept verbatim`() {
        val parser = parserAcceptingAllFiles()
        val plain = "https://example.com/files/big-iso.iso?size=2g&amp=1#anchor"
        assertEquals(ExternalOpenRequest.WebLink(plain), parser.classifyForDrop(plain).getOrNull())
        // Surrounding whitespace from a clipboard or a dragged selection is trimmed, the rest is untouched.
        assertEquals(
            ExternalOpenRequest.WebLink("http://mirror.example/a.bin"),
            parser.classifyForDrop("  http://mirror.example/a.bin\n").getOrNull(),
        )
    }

    @Test
    fun `magnets and torrent paths keep working through the drop path`() {
        val parser = parserAcceptingAllFiles()
        val hex40 = "0123456789abcdef0123456789abcdef01234567"
        assertTrue(parser.classifyForDrop("magnet:?xt=urn:btih:$hex40&dn=x").getOrNull() is ExternalOpenRequest.MagnetUri)
        assertTrue(parser.classifyForDrop("/tmp/movie.torrent").getOrNull() is ExternalOpenRequest.TorrentFile)
    }

    @Test
    fun `the argv path still rejects web links`() {
        // The drop widening must never leak into the launch vocabulary.
        val parser = parserAcceptingAllFiles()
        val result = parser.parse(listOf("https://example.com/file.torrent"))
        assertEquals(emptyList(), result.requests)
        assertEquals(RejectionReason.UNSUPPORTED_SCHEME, result.rejected.single().reason)
    }

    @Test
    fun `other schemes are still refused even on the drop path`() {
        val parser = parserAcceptingAllFiles()
        assertEquals(RejectionReason.UNSUPPORTED_SCHEME, classifyReason(parser, "ftp://example.com/x.torrent"))
        assertEquals(RejectionReason.UNSUPPORTED_SCHEME, classifyReason(parser, "ed2k://|file|a.torrent|"))
        assertEquals(RejectionReason.UNSUPPORTED_SCHEME, classifyReason(parser, "https:"))
        assertEquals(RejectionReason.UNSUPPORTED_SCHEME, classifyReason(parser, "https:///no-authority"))
    }

    @Test
    fun `malformed web links are refused with the precise reason`() {
        val parser = parserAcceptingAllFiles()
        assertEquals(
            RejectionReason.INVALID_CHARACTERS,
            classifyReason(parser, "https://example.com/a\u0000b"),
        )
        assertEquals(
            RejectionReason.TOO_LONG,
            classifyReason(parser, "https://example.com/" + "x".repeat(9000)),
        )
    }

    @Test
    fun `blank drops are accepted-but-noop, never errors`() {
        assertNull(parserAcceptingAllFiles().classifyForDrop("   ").getOrNull())
    }

    @Test
    fun `displaySource round-trips a web link as its url`() {
        val url = "https://example.com/file.bin"
        assertEquals(url, (ExternalOpenRequest.WebLink(url) as ExternalOpenRequest).displaySource)
    }
}
