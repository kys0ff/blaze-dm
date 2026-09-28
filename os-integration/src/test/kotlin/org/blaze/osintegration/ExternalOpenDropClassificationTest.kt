package org.blaze.osintegration

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The widened classification contract used by in-app surfaces (window drag-and-drop, clipboard
 * monitoring): plain `http(s)` links are accepted *there* while the OS launch path keeps refusing
 * them, and the window drop additionally turns existing link-list text files into batch imports
 * and any other existing file/folder into a candidate partial download. The clipboard keeps the
 * narrower link-only vocabulary: a merely *copied* path is not an import intent. The file probe
 * is stubbed so every path branch stays deterministic.
 */
class ExternalOpenDropClassificationTest {

    private fun parserAcceptingAllFiles(): ExternalOpenParser = ExternalOpenParser { PathProbe.REGULAR_FILE }

    private fun classifyReason(parser: ExternalOpenParser, raw: String): RejectionReason? =
        parser.classifyForDrop(raw).exceptionOrNull()?.let { e ->
            RejectionReason.entries.first { it.name == e.message }
        }

    private fun clipboardReason(parser: ExternalOpenParser, raw: String): RejectionReason? =
        parser.classifyForClipboard(raw).exceptionOrNull()?.let { e ->
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
    fun `link-list text files become batch import requests on the drop path`() {
        val parser = parserAcceptingAllFiles()
        assertEquals(
            ExternalOpenRequest.LinkListFile(Paths.get("/home/user/links.txt").toAbsolutePath().normalize()),
            parser.classifyForDrop("/home/user/links.txt").getOrNull(),
        )
        assertTrue(parser.classifyForDrop("/tmp/my.list").getOrNull() is ExternalOpenRequest.LinkListFile)
        assertTrue(parser.classifyForDrop("/tmp/my.urls").getOrNull() is ExternalOpenRequest.LinkListFile)
    }

    @Test
    fun `any other existing file or folder is a partial-download candidate on the drop path`() {
        // Detection is by content downstream (§23), so the classifier only promises existence.
        val parser = parserAcceptingAllFiles()
        assertTrue(parser.classifyForDrop("/tmp/big.iso.part").getOrNull() is ExternalOpenRequest.PartialDownload)
        assertTrue(parser.classifyForDrop("/tmp/photo.png").getOrNull() is ExternalOpenRequest.PartialDownload)
        val folderParser = ExternalOpenParser { PathProbe.DIRECTORY }
        assertTrue(folderParser.classifyForDrop("/home/user/Downloads/movie-folder").getOrNull() is ExternalOpenRequest.PartialDownload)
    }

    @Test
    fun `missing drops are refused with the precise reason`() {
        val parser = ExternalOpenParser { PathProbe.MISSING }
        assertEquals(RejectionReason.FILE_NOT_FOUND, classifyReason(parser, "/tmp/gone.txt"))
        assertEquals(RejectionReason.FILE_NOT_FOUND, classifyReason(parser, "/tmp/gone.torrent"))
    }

    @Test
    fun `the clipboard vocabulary takes links and torrents but never path artifacts`() {
        val parser = parserAcceptingAllFiles()
        assertTrue(parser.classifyForClipboard("https://example.com/f.bin").getOrNull() is ExternalOpenRequest.WebLink)
        assertTrue(parser.classifyForDrop("/tmp/links.txt").getOrNull() is ExternalOpenRequest.LinkListFile)
        // The very same path must *not* become an import request just because it was copied.
        assertEquals(RejectionReason.NOT_A_TORRENT_FILE, clipboardReason(parser, "/tmp/links.txt"))
        assertEquals(RejectionReason.NOT_A_TORRENT_FILE, clipboardReason(parser, "/tmp/big.iso.part"))
        assertNull(parser.classifyForClipboard("/tmp/links.txt").getOrNull())
    }

    @Test
    fun `the argv path still rejects web links and artifacts alike`() {
        // The drop widening must never leak into the launch vocabulary.
        val parser = parserAcceptingAllFiles()
        val result = parser.parse(listOf("https://example.com/file.torrent", "/home/user/links.txt"))
        assertEquals(emptyList(), result.requests)
        assertEquals(
            listOf(RejectionReason.UNSUPPORTED_SCHEME, RejectionReason.NOT_A_TORRENT_FILE),
            result.rejected.map { it.reason },
        )
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

    @Test
    fun `displaySource round-trips the artifact variants as their absolute path`() {
        val path = Paths.get("/home/user/links.txt").toAbsolutePath().normalize()
        assertEquals(path.toString(), (ExternalOpenRequest.LinkListFile(path) as ExternalOpenRequest).displaySource)
        assertEquals(path.toString(), (ExternalOpenRequest.PartialDownload(path) as ExternalOpenRequest).displaySource)
    }
}
