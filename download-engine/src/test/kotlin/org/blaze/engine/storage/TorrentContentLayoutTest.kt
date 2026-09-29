package org.blaze.engine.storage

import bt.data.StorageUnit
import bt.metainfo.Torrent
import bt.metainfo.TorrentFile
import bt.metainfo.TorrentSource
import bt.tracker.AnnounceKey
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a torrent's files land, and what a hostile torrent cannot reach.
 *
 * Guarded here because these few lines decide the whole visible shape of a torrent download: one
 * folder per download, named after the download, holding the torrent's content exactly as the
 * torrent itself describes it. The bug pinned below is the second, nested copy of that name
 * (`~/Downloads/Big.Buck/Big.Buck/…`) that `bt`'s file storage adds on top of the folder Blaze
 * already made - and the reason the layout can no longer be handed back to the library.
 */
class TorrentContentLayoutTest {

    @Test
    fun `a multi-file torrent writes inside its own folder, not one level deeper`() {
        val base = Files.createTempDirectory("blaze-torrent-layout")
        val root = base.resolve("Big.Buck")
        try {
            val torrent = torrent("Big.Buck", file("subs", "en.srt"), file("bunny.mp4"))
            // The library derives a root level from the metainfo name; Blaze must not.
            write(storageUnit(root, torrent, torrent.files[0]), "subtitles")

            assertTrue(Files.exists(root.resolve("subs/en.srt")), "expected the torrent's own subfolder")
            assertFalse(Files.exists(root.resolve("Big.Buck")), "the torrent name must not become a folder")
            assertEquals("subtitles", Files.readString(root.resolve("subs/en.srt")))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a renamed download still receives the torrent's content under its new folder`() {
        val base = Files.createTempDirectory("blaze-torrent-layout")
        val root = base.resolve("my-renamed-download")
        try {
            // The metainfo answers with a different name than the folder the user picked.
            val torrent = torrent("Some.Other.Name", file("disc1.iso"))
            write(storageUnit(root, torrent, torrent.files[0]), "data")

            assertTrue(Files.exists(root.resolve("disc1.iso")))
            assertFalse(Files.exists(base.resolve("Some.Other.Name")))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a single-file torrent lands straight in the download's folder`() {
        val root = Path.of("/home/user/Downloads/pause-resume")

        assertEquals(root.resolve("movie.mkv"), torrentContentPath(root, listOf("movie.mkv")))
    }

    @Test
    fun `path elements that climb out are folded back inside`() {
        val root = Path.of("/home/user/Downloads/Big.Buck")

        val escaped = torrentContentPath(root, listOf("..", "..", "etc", "passwd"))

        assertTrue(escaped.startsWith(root), "$escaped left the download's folder")
        assertEquals(root.resolve("_/_/etc/passwd"), escaped)
    }

    @Test
    fun `an absolute path element cannot redirect the write either`() {
        val root = Path.of("/home/user/Downloads/Big.Buck")

        // The library's normaliser treats a leading separator as an empty first name; the result
        // stays under the destination either way.
        assertEquals(root.resolve("_/etc/passwd"), torrentContentPath(root, listOf("/etc/passwd")))
    }

    @Test
    fun `awkward names are normalised exactly the way the library normalises them`() {
        val root = Path.of("/home/user/Downloads/torrent")

        assertEquals(root.resolve("movie.mkv"), torrentContentPath(root, listOf(" movie.mkv. ")))
        assertEquals(root.resolve("a/_/b"), torrentContentPath(root, listOf("a//b")))
        assertEquals(root.resolve("a/b"), torrentContentPath(root, listOf("a/b")))
        assertEquals(root.resolve("_"), torrentContentPath(root, emptyList()))
    }

    /**
     * The table from `bt`'s file-storage documentation. Blaze reimplements those rules (the library's
     * normaliser is package-private), so the table is the contract this has to satisfy - and matching
     * it is what keeps a torrent's already-downloaded data addressable after the switch.
     */
    @Test
    fun `the library's documented name normalisation is reproduced`() {
        val root = Path.of("/downloads")

        fun normalised(vararg elements: String) =
            torrentContentPath(root, elements.toList()).toString().removePrefix("/downloads/")

        assertEquals("a/b/c", normalised("a", "b", "c"))
        assertEquals("a/b/c", normalised(" a", "  b ", "c"))
        assertEquals(".a/.b", normalised(".a", ".b"))
        assertEquals("a/.b", normalised("a.", ".b."))
        assertEquals("_", normalised(""))
        assertEquals("a/_/b", normalised("a//b"))
        assertEquals("_", normalised("."))
        assertEquals("_", normalised(".."))
        assertEquals("_/_/etc/passwd", normalised("..", "..", "etc", "passwd"))
        assertEquals("_/_", normalised("/"))
        assertEquals("_/a/b/c", normalised("/a/b/c"))
    }

    // ---- helpers ----

    private fun storageUnit(root: Path, torrent: Torrent, file: TorrentFile): StorageUnit =
        TorrentContentStorage(root).getUnit(torrent, file)

    private fun write(unit: StorageUnit, content: String) {
        unit.writeBlock(ByteBuffer.wrap(content.toByteArray()), 0L)
        unit.close()
    }

    // ---- metainfo stubs: only the pieces the layout looks at are real ----

    private fun torrent(name: String, vararg files: TorrentFile): Torrent = object : Torrent {
        override fun getSource(): TorrentSource = throw UnsupportedOperationException()
        override fun getAnnounceKey(): Optional<AnnounceKey> = Optional.empty()
        override fun getTorrentId(): bt.metainfo.TorrentId = throw UnsupportedOperationException()
        override fun getName(): String = name
        override fun getChunkSize(): Long = 0
        override fun getChunkHashes(): Iterable<ByteArray> = emptyList()
        override fun getSize(): Long = files.sumOf { it.size }
        override fun getFiles(): List<TorrentFile> = files.toList()
        override fun isPrivate(): Boolean = false
        override fun getCreationDate(): Optional<Instant> = Optional.empty()
        override fun getCreatedBy(): Optional<String> = Optional.empty()
    }

    private fun file(vararg elements: String): TorrentFile = object : TorrentFile {
        override fun getSize(): Long = 64L
        override fun getPathElements(): List<String> = elements.toList()
        override fun toString(): String = elements.joinToString("/")
    }
}
