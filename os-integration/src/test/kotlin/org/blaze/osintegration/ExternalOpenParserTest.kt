package org.blaze.osintegration

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Argument-parsing contract of the launcher: raw OS/browser argv in, validated
 * [ExternalOpenRequest]s (or typed rejections) out. The file probe is stubbed so every
 * path branch is deterministic without touching a real disk.
 */
class ExternalOpenParserTest {

    private fun parserAcceptingAllFiles(): ExternalOpenParser = ExternalOpenParser { PathProbe.REGULAR_FILE }

    private val hex40 = "0123456789abcdef0123456789abcdef01234567"

    private fun classifyReason(parser: ExternalOpenParser, raw: String): RejectionReason? =
        parser.classify(raw).exceptionOrNull()?.let { reasonOf(it) }

    private fun reasonOf(e: Throwable): RejectionReason =
        RejectionReason.entries.first { it.name == e.message }

    // ── classification basics ────────────────────────────────────────────────────────────────

    @Test
    fun `normal launch carries no requests and no rejections`() {
        val result = parserAcceptingAllFiles().parse(emptyList())
        assertEquals(emptyList(), result.requests)
        assertEquals(emptyList(), result.rejected)
        assertEquals(false, result.allowMultipleInstances)
    }

    @Test
    fun `a torrent path becomes a TorrentFile with an absolute normalized path`() {
        val parser = parserAcceptingAllFiles()
        // "." drops out, "downloads/.." cancels - the user means the same file either way.
        val request = parser.classify("/home/user/./downloads/movie.torrent").getOrNull()
        assertEquals(
            ExternalOpenRequest.TorrentFile(Paths.get("/home/user/downloads/movie.torrent")),
            request,
        )
        val dotDot = parser.classify("/home/user/downloads/../movie.torrent").getOrNull()
        assertEquals(
            ExternalOpenRequest.TorrentFile(Paths.get("/home/user/movie.torrent")),
            dotDot,
        )
    }

    @Test
    fun `the torrent extension is matched case insensitively`() {
        val request = parserAcceptingAllFiles().classify("/tmp/Movie.TORRENT").getOrNull()
        assertTrue(request is ExternalOpenRequest.TorrentFile)
    }

    @Test
    fun `paths with spaces and unicode names survive classification untouched`() {
        val parser = parserAcceptingAllFiles()
        val spaced = parser.classify("/home/user/My Downloads/big file (1).torrent").getOrNull()
        assertEquals(
            ExternalOpenRequest.TorrentFile(Paths.get("/home/user/My Downloads/big file (1).torrent")),
            spaced,
        )
        val unicode = parser.classify("/home/user/виды/тест—ξ torrent.torrent").getOrNull()
        assertTrue(unicode is ExternalOpenRequest.TorrentFile)
        assertEquals("/home/user/виды/тест—ξ torrent.torrent", unicode.path.toString())
    }

    @Test
    fun `a drive-letter path is a path, not a URI scheme`() {
        // "C:" looks like a scheme to a naive check; the parser must classify it as a file path.
        val request = parserAcceptingAllFiles().classify("C:\\Downloads\\movie.torrent").getOrNull()
        assertTrue(request is ExternalOpenRequest.TorrentFile)
    }

    @Test
    fun `a file URI for a torrent is converted to its local path`() {
        val request = parserAcceptingAllFiles().classify("file:///home/user/down/movie.torrent").getOrNull()
        assertTrue(request is ExternalOpenRequest.TorrentFile)
        assertEquals("/home/user/down/movie.torrent", request.path.toString())
    }

    // ── magnets ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a valid magnet is accepted verbatim with every parameter preserved`() {
        val uri = "magnet:?xt=urn:btih:$hex40&dn=My%20Movie%202026&tr=udp%3A%2F%2Ftracker%3A6969" +
                "&tr=http%3A%2F%2Fsecond%2Fannounce&ws=https%3A%2F%2Fmirror%2Ffile"
        val request = parserAcceptingAllFiles().classify(uri).getOrNull()
        // Byte-for-byte equality: no truncation, no re-encoding, dropped or reordered parameters.
        assertEquals(ExternalOpenRequest.MagnetUri(uri), request)
    }

    @Test
    fun `a magnet with a raw (unencoded) space in dn is still accepted`() {
        // Some lossy desktop launchers deliver the URI without encoding; text parsing keeps it working.
        val uri = "magnet:?xt=urn:btih:$hex40&dn=My Movie"
        assertTrue(parserAcceptingAllFiles().classify(uri).getOrNull() is ExternalOpenRequest.MagnetUri)
    }

    @Test
    fun `a base32 info hash is accepted`() {
        val uri = "magnet:?xt=urn:btih:GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&dn=x"
        assertTrue(parserAcceptingAllFiles().classify(uri).getOrNull() is ExternalOpenRequest.MagnetUri)
    }

    @Test
    fun `malformed magnets are rejected with the precise reason`() {
        val parser = parserAcceptingAllFiles()
        assertEquals(RejectionReason.MISSING_BTIH, classifyReason(parser, "magnet:?dn=only-name"))
        assertEquals(RejectionReason.MISSING_BTIH, classifyReason(parser, "magnet:"))
        assertEquals(RejectionReason.MISSING_BTIH, classifyReason(parser, "magnet:?xt=urn:btih:short"))
        assertEquals(
            RejectionReason.MISSING_BTIH,
            classifyReason(parser, "magnet:?xt=tttttttttttttttttttttttttttttttttttttttt&dn=x"),
            "40 chars that are not hex must not pass as a btih",
        )
        assertEquals(
            RejectionReason.MALFORMED_MAGNET,
            classifyReason(parser, "magnet:?xt=urn:btih:$hex40&dn=%zz broken"),
            "invalid percent-encoding escapes",
        )
    }

    @Test
    fun `control characters never cross the boundary`() {
        val parser = parserAcceptingAllFiles()
        assertEquals(RejectionReason.INVALID_CHARACTERS, classifyReason(parser, "magnet:?xt=urn:btih:$hex40&dn=a\u0000b"))
        assertEquals(RejectionReason.INVALID_CHARACTERS, classifyReason(parser, "/tmp/a\u001B.torrent"))
    }

    @Test
    fun `absurdly long inputs are rejected before any parsing work`() {
        val parser = parserAcceptingAllFiles()
        val longMagnet = "magnet:?xt=urn:btih:$hex40&tr=" + "a".repeat(9000)
        assertEquals(RejectionReason.TOO_LONG, classifyReason(parser, longMagnet))
        val longPath = "/" + "x".repeat(5000) + ".torrent"
        assertEquals(RejectionReason.TOO_LONG, classifyReason(parser, longPath))
    }

    // ── unsupported / unusable arguments ──────────────────────────────────────────────────────

    @Test
    fun `foreign URI schemes are refused, including lookalikes`() {
        val parser = parserAcceptingAllFiles()
        assertEquals(RejectionReason.UNSUPPORTED_SCHEME, classifyReason(parser, "https://example.com/file.torrent"))
        assertEquals(RejectionReason.UNSUPPORTED_SCHEME, classifyReason(parser, "ftp://example.com/x.torrent"))
        assertEquals(RejectionReason.UNSUPPORTED_SCHEME, classifyReason(parser, "ed2k://|file|a.torrent|"))
        assertEquals(RejectionReason.UNSUPPORTED_SCHEME, classifyReason(parser, "magnetx:?xt=urn:btih:$hex40"))
    }

    @Test
    fun `non-torrent and missing or unreadable files are refused with the file-system reason`() {
        val parser = ExternalOpenParser { probe ->
            when (probe.fileName.toString()) {
                "present.torrent" -> PathProbe.REGULAR_FILE
                "folder.torrent" -> PathProbe.DIRECTORY
                "locked.torrent" -> PathProbe.NOT_PERMITTED
                else -> PathProbe.MISSING
            }
        }
        assertEquals(RejectionReason.NOT_A_TORRENT_FILE, classifyReason(parser, "/tmp/notes.txt"))
        assertTrue(parser.classify("/tmp/present.torrent").getOrNull() is ExternalOpenRequest.TorrentFile)
        assertEquals(RejectionReason.FILE_NOT_FOUND, classifyReason(parser, "/tmp/gone.torrent"))
        assertEquals(RejectionReason.FILE_NOT_READABLE, classifyReason(parser, "/tmp/folder.torrent"))
        assertEquals(RejectionReason.FILE_NOT_READABLE, classifyReason(parser, "/tmp/locked.torrent"))
    }

    @Test
    fun `options and flags are ignored, not rejected as attacks`() {
        // macOS hands over "-psn_0_12345"; dev runs may pass JVM flags. They must not pollute rejections.
        val result = parserAcceptingAllFiles().parse(listOf("-psn_0_12345"))
        assertEquals(emptyList(), result.requests)
        assertEquals(RejectionReason.IGNORED_OPTION, result.rejected.single().reason)
    }

    // ── multiplicity and the whole-argv shape ─────────────────────────────────────────────────

    @Test
    fun `several files and a magnet arrive in order as separate requests`() {
        val result = parserAcceptingAllFiles().parse(
            listOf(
                "/tmp/one.torrent",
                "magnet:?xt=urn:btih:$hex40&dn=first",
                "/tmp/two.torrent",
            )
        )
        assertEquals(
            listOf(
                ExternalOpenRequest.TorrentFile(Paths.get("/tmp/one.torrent")),
                ExternalOpenRequest.MagnetUri("magnet:?xt=urn:btih:$hex40&dn=first"),
                ExternalOpenRequest.TorrentFile(Paths.get("/tmp/two.torrent")),
            ),
            result.requests,
        )
    }

    @Test
    fun `one bad argument never loses the good ones`() {
        // Only ok.torrent exists on this simulated machine.
        val parser = ExternalOpenParser { if (it.fileName.toString() == "ok.torrent") PathProbe.REGULAR_FILE else PathProbe.MISSING }
        val result = parser.parse(
            listOf("/tmp/ok.torrent", "ftp://evil/x", "/tmp/gone.torrent", "magnet:?xt=urn:btih:$hex40")
        )
        assertEquals(2, result.requests.size)
        assertEquals(listOf("ftp://evil/x", "/tmp/gone.torrent"), result.rejected.map { it.raw })
    }

    @Test
    fun `the new-instance escape hatch is recognised but not treated as a resource`() {
        val result = parserAcceptingAllFiles().parse(listOf("--new-instance", "/tmp/ok.torrent"))
        assertTrue(result.allowMultipleInstances)
        assertEquals(1, result.requests.size)
    }

    @Test
    fun `the cleanup option is a known argument, not a resource or a rejection`() {
        // `blaze --cleanup` is handled by the launcher before anything is parsed; recognising the flag
        // here is what keeps it out of the "unsupported launch argument" log after every cleanup run.
        val result = parserAcceptingAllFiles().parse(listOf(ExternalOpenParser.OPTION_CLEANUP))

        assertTrue(result.requests.isEmpty(), "cleanup opens nothing")
        assertTrue(result.rejected.isEmpty(), "it must not read as a bad argument: ${result.rejected}")
        assertFalse(result.allowMultipleInstances)
    }

    // ── routing shape: what the download workflow downstream receives ────────────────────────

    @Test
    fun `displaySource is exactly the string the existing add-download workflow accepts`() {
        val torrent = ExternalOpenRequest.TorrentFile(Paths.get("/tmp/a b.torrent"))
        assertEquals("/tmp/a b.torrent", torrent.displaySource)
        assertTrue(torrent.displaySource.endsWith(".torrent"))

        val magnet = ExternalOpenRequest.MagnetUri("magnet:?xt=urn:btih:$hex40&dn=x&tr=udp%3A%2F%2Ft%3A6969")
        assertEquals(magnet.uri, magnet.displaySource)
        assertTrue(magnet.displaySource.startsWith("magnet:"))
    }
}
