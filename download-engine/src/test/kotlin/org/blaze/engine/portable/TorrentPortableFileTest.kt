package org.blaze.engine.portable

import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-format coverage for the torrent portable marker: the metainfo must round-trip exactly, every
 * structural check must reject hostile or truncated input, and an overwrite must be a clean replace.
 * The full "move a torrent folder and resume with `bt`" behaviour is exercised by the engine import
 * test; this proves the container itself is trustworthy.
 */
class TorrentPortableFileTest {

    private fun torrentManifest(path: String = "album/track 01.flac") = PortableManifest.Torrent(
        name = "Album",
        torrentFile = ByteArray(256) { (it * 31 + 7).toByte() },
        contentLength = 5_000_000,
        pieceLength = 16384,
        pieceCount = 306,
        trackers = listOf("http://tracker.example/announce"),
        files = listOf(PortableTorrentFile(path, 5_000_000)),
        completedPieces = BitSet().apply { set(0); set(1); set(200) },
        multiFile = true
    )

    private fun tempDir(): Path = Files.createTempDirectory("blaze-torrent-marker")

    @Test
    fun `a marker round-trips the canonical torrent bytes and manifest`() {
        val dir = tempDir()
        val marker = TorrentPortableFile.markerPath(dir)
        val manifest = torrentManifest()
        TorrentPortableFile.write(marker, manifest, generation = 3)

        val record = assertNotNull(TorrentPortableFile.read(marker))
        assertEquals(3L, record.generation)
        assertContentEquals(manifest.torrentFile, record.manifest.torrentFile)
        assertEquals(manifest.name, record.manifest.name)
        assertEquals(manifest.files, record.manifest.files)
        assertEquals(3, record.manifest.completedPieces.cardinality())
        assertTrue(Files.exists(marker))
    }

    @Test
    fun `read returns null for an absent or foreign marker`() {
        val dir = tempDir()
        assertNull(TorrentPortableFile.read(TorrentPortableFile.markerPath(dir)))
        val junk = dir.resolve(TorrentPortableFile.MARKER_NAME)
        Files.write(junk, ByteArray(64) { 0x5A })
        assertNull(TorrentPortableFile.read(junk), "random bytes are never a valid marker")
    }

    @Test
    fun `a corrupted CRC is rejected`() {
        val dir = tempDir()
        val marker = TorrentPortableFile.markerPath(dir)
        TorrentPortableFile.write(marker, torrentManifest(), generation = 1)
        val bytes = Files.readAllBytes(marker)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte() // flip the last payload byte
        Files.write(marker, bytes)
        assertNull(TorrentPortableFile.read(marker))
    }

    @Test
    fun `a truncated header is rejected without throwing`() {
        val dir = tempDir()
        val marker = TorrentPortableFile.markerPath(dir)
        Files.write(marker, ByteArray(10))
        assertNull(TorrentPortableFile.read(marker))
    }

    @Test
    fun `an absurd declared payload length is rejected`() {
        val dir = tempDir()
        val marker = TorrentPortableFile.markerPath(dir)
        TorrentPortableFile.write(marker, torrentManifest(), generation = 1)
        val bytes = Files.readAllBytes(marker)
        // Overwrite the int length field (offset 8+2+2+8 = 20) with a huge value.
        java.nio.ByteBuffer.wrap(bytes).putInt(20, Int.MAX_VALUE)
        Files.write(marker, bytes)
        assertNull(TorrentPortableFile.read(marker), "a hostile length must not drive a huge allocation")
    }

    @Test
    fun `rewriting the marker replaces it atomically`() {
        val dir = tempDir()
        val marker = TorrentPortableFile.markerPath(dir)
        TorrentPortableFile.write(marker, torrentManifest("a/one.bin"), generation = 1)
        TorrentPortableFile.write(marker, torrentManifest("b/two.bin"), generation = 2)

        val record = assertNotNull(TorrentPortableFile.read(marker))
        assertEquals(2L, record.generation)
        assertEquals("b/two.bin", record.manifest.files.single().relativePath)
        Files.list(dir).use { entries ->
            assertTrue(
                entries.noneMatch { it.fileName.toString().endsWith(".tmp") },
                "an atomic replace must leave no temp file behind"
            )
        }
    }

    @Test
    fun `delete removes the marker and any leftover temp`() {
        val dir = tempDir()
        val marker = TorrentPortableFile.markerPath(dir)
        TorrentPortableFile.write(marker, torrentManifest(), generation = 1)
        TorrentPortableFile.delete(marker)
        assertNull(TorrentPortableFile.read(marker))
        assertTrue(!Files.exists(marker))
    }
}
