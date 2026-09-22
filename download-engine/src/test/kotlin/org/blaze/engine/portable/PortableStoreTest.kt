package org.blaze.engine.portable

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the portable format as pure file logic: layout, double-buffered crash recovery,
 * finalization stripping, and hostile-input rejection. No network or engine types involved.
 */
class PortableStoreTest {

    private fun httpManifest(total: Long, chunkSize: Long, done: Int, chunkCount: Int, validator: String? = "\"etag-1\"") =
        PortableManifest.Http(
            originalUrl = "https://example.com/file.bin",
            resolvedUrl = null,
            contentLength = total,
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            acceptsRanges = true,
            validator = validator,
            completedChunks = BitSet().apply { for (i in 0 until done) set(i) }
        )

    @Test
    fun `create then inspect recovers the manifest and content length`() = withTempDir { dir ->
        val total = 1024L * 1024
        val partial = dir.resolve("file.part")
        Files.write(partial, ByteArray(1024))
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 64 * 1024, 3, 16))

        val artifact = assertNotNull(store.inspect())
        assertEquals(PortableKind.HTTP, artifact.kind)
        assertEquals(total, artifact.contentLength)
        assertEquals(1L, artifact.generation)
        val manifest = assertNotNull(artifact.manifest) as PortableManifest.Http
        assertEquals(3, manifest.completedChunks.cardinality())
        // The partial carries the reserved metadata region on top of the content.
        assertEquals(PortableFormat.partialLength(total, PortableFormat.HTTP_SLOT_SIZE), Files.size(partial))
    }

    @Test
    fun `checkpoints alternate slots and increase the generation`() = withTempDir { dir ->
        val total = 4L * 1024 * 1024
        val partial = dir.resolve("file.part")
        Files.write(partial, ByteArray(0))
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 64 * 1024, 0, 64))

        store.checkpoint(httpManifest(total, 64 * 1024, 10, 64))
        store.checkpoint(httpManifest(total, 64 * 1024, 25, 64))

        val artifact = assertNotNull(store.inspect())
        assertEquals(3L, artifact.generation)
        assertEquals(25, (artifact.manifest as PortableManifest.Http).completedChunks.cardinality())
    }

    @Test
    fun `a torn latest slot falls back to the previous generation`() = withTempDir { dir ->
        val total = 4L * 1024 * 1024
        val partial = dir.resolve("file.part")
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 64 * 1024, 5, 64))
        store.checkpoint(httpManifest(total, 64 * 1024, 40, 64)) // gen 2 into slot B

        // Corrupt the newest record (slot B) the way a torn write would: zero its magic so its
        // header no longer validates.
        val slotB = total + PortableFormat.HTTP_SLOT_SIZE
        Files.newByteChannel(partial, StandardOpenOption.WRITE, StandardOpenOption.READ).use { ch ->
            ch.position(slotB)
            ch.write(java.nio.ByteBuffer.allocate(8))
        }

        val artifact = assertNotNull(store.inspect())
        // The intact older slot A (gen 1) is trusted again.
        assertEquals(1L, artifact.generation)
        assertEquals(5, (artifact.manifest as PortableManifest.Http).completedChunks.cardinality())
    }

    @Test
    fun `corrupting both locator mirrors makes the file non-portable`() = withTempDir { dir ->
        val total = 1024L
        val partial = dir.resolve("file.part")
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 512, 1, 2))
        val size = Files.size(partial)
        // Zero out the tail locator area.
        Files.newByteChannel(partial, StandardOpenOption.WRITE).use { ch ->
            ch.position(size - 2L * PortableFormat.LOCATOR_SIZE)
            ch.write(java.nio.ByteBuffer.allocate(2 * PortableFormat.LOCATOR_SIZE))
        }
        assertNull(store.inspect())
    }

    @Test
    fun `finalize strips the region leaving exactly the content`() = withTempDir { dir ->
        val content = ByteArray(5000) { (it % 251).toByte() }
        val total = content.size.toLong()
        val partial = dir.resolve("file.part")
        Files.write(partial, content)
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 1000, 5, 5))
        store.checkpoint(httpManifest(total, 1000, 5, 5))

        store.finalize(total)

        assertEquals(total, Files.size(partial))
        assertContentEquals(content, Files.readAllBytes(partial))
        assertNull(store.inspect(), "no trailer must remain after finalize")
    }

    @Test
    fun `inspect returns null for a completed file and for a foreign file`() = withTempDir { dir ->
        val finished = dir.resolve("done.bin")
        Files.write(finished, ByteArray(100))
        assertNull(inspectPortableFile(finished))

        val foreign = dir.resolve("random.dat")
        Files.write(foreign, ByteArray(4096) { (it * 7).toByte() })
        assertNull(inspectPortableFile(foreign))
    }

    @Test
    fun `stale artifact exposes content length with no manifest`() = withTempDir { dir ->
        val total = 1024L
        val partial = dir.resolve("file.part")
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 512, 0, 2))
        // Wipe both slots so the locator is valid but no record survives.
        Files.newByteChannel(partial, StandardOpenOption.WRITE).use { ch ->
            ch.position(total); ch.write(java.nio.ByteBuffer.allocate(2 * PortableFormat.HTTP_SLOT_SIZE.toInt()))
        }
        val artifact = assertNotNull(store.inspect())
        assertTrue(artifact.stale)
        assertNull(artifact.manifest)
        assertEquals(total, artifact.contentLength)
    }

    @Test
    fun `decoding rejects an absolute or traversing torrent path`() {
        val bad = PortableManifest.Torrent(
            name = "t", torrentFile = ByteArray(8) { 1 }, contentLength = 10,
            pieceLength = 2, pieceCount = 5, trackers = emptyList(),
            files = listOf(PortableTorrentFile("../../etc/passwd", 10)),
            completedPieces = BitSet(), multiFile = true
        )
        // Encoding keeps the raw path; the decoder is the security boundary and must refuse it.
        val bytes = ManifestCodec.encode(bad)
        assertFailsWith<PortableFormatException> { ManifestCodec.decode(bytes) }
    }

    @Test
    fun `relative torrent paths survive a round trip and are validated`() {
        val m = PortableManifest.Torrent(
            name = "Album", torrentFile = ByteArray(64) { (it % 13).toByte() }, contentLength = 1_000_000,
            pieceLength = 16384, pieceCount = 62, trackers = listOf("http://tracker/announce"),
            files = listOf(PortableTorrentFile("cd1/track 01.mp3", 500_000), PortableTorrentFile("cd1/track 02.mp3", 500_000)),
            completedPieces = BitSet().apply { set(0); set(10) }, multiFile = true
        )
        val decoded = ManifestCodec.decode(ManifestCodec.encode(m)) as PortableManifest.Torrent
        assertEquals(m.files, decoded.files)
        assertEquals(2, decoded.completedPieces.cardinality())
        assertContentEquals(m.torrentFile, decoded.torrentFile)
    }

    // ------------------------------------------------------------------ helpers

    private inline fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("blaze-portable-test")
        try {
            block(dir)
        } finally {
            runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
