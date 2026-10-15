package org.blaze.engine.storage

import bt.data.InsufficientStorageException
import bt.data.PreallocationMode
import bt.data.StorageUnit
import bt.metainfo.Torrent
import bt.metainfo.TorrentFile
import bt.metainfo.TorrentId
import bt.metainfo.TorrentSource
import bt.tracker.AnnounceKey
import org.blaze.engine.api.DownloadError
import org.blaze.engine.network.toTorrentDownloadError
import org.blaze.engine.settings.TorrentPreallocation
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TOR-2: Blaze's torrent storage reserving file length up front.
 *
 * The mechanics live in the fork's shared `Preallocation` utility (covered by its own tests); what
 * is pinned here is the engine's own contract: the setting maps to the right mode, the storage
 * applies it to the paths *it* chose (including the folded-upon-normalisation ones), the write path
 * through a preallocated unit stays correct, and a rejected preallocation surfaces as
 * [DownloadError.DiskFull] rather than a retryable network blip.
 */
class TorrentPreallocationTest {

    // ---- setting -> fork mode ----

    @Test
    fun `each engine setting maps to the matching fork mode`() {
        assertSame(PreallocationMode.NONE, TorrentPreallocation.NONE.toForkMode())
        assertSame(PreallocationMode.SPARSE, TorrentPreallocation.SPARSE.toForkMode())
        assertSame(PreallocationMode.FULL, TorrentPreallocation.FULL.toForkMode())
    }

    @Test
    fun `settings default to no preallocation`() {
        // Deliberate (see the KDoc on DownloadSettings): SPARSE would size every file to its full
        // length and neutralize both the absent-block answer and the resume size fingerprint.
        assertEquals(TorrentPreallocation.NONE, org.blaze.engine.settings.DownloadSettings().torrentPreallocation)
    }

    // ---- storage behavior ----

    @Test
    fun `the default mode reserves nothing up front`() {
        val base = Files.createTempDirectory("blaze-prealloc-none")
        try {
            val file = file(64 * 1024, "movie.mkv")
            storage(base, TorrentPreallocation.NONE).getUnit(torrent("movie.torrent", file), file).close()
            assertFalse(
                Files.exists(base.resolve("movie.mkv")),
                "NONE must keep the historical grow-on-write behavior"
            )
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sparse sizes the file to its full length at the storage's own path`() {
        val base = Files.createTempDirectory("blaze-prealloc-sparse")
        try {
            val file = file(1024 * 1024, "subs", "en.srt")
            storage(base, TorrentPreallocation.SPARSE).getUnit(torrent("bundle", file), file).close()

            val created = base.resolve("subs/en.srt")
            assertTrue(Files.exists(created), "SPARSE must create the file when the unit opens")
            assertEquals(1024 * 1024L, Files.size(created))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `full writes real zeros but keeps the bytes a partial download already has`() {
        val base = Files.createTempDirectory("blaze-prealloc-full")
        try {
            val size = 200_000L
            val file = file(size, "data.bin")
            val target = base.resolve("data.bin")
            // A restarted download: the first 1000 bytes are already on disk and must survive.
            Files.write(target, ByteArray(1000) { 0x5A })

            storage(base, TorrentPreallocation.FULL).getUnit(torrent("single", file), file).close()

            assertEquals(size, Files.size(target))
            val all = Files.readAllBytes(target)
            assertTrue(all.copyOfRange(0, 1000).all { it == 0x5A.toByte() }, "existing bytes must not be overwritten")
            assertTrue(all.copyOfRange(1000, all.size).all { it == 0.toByte() }, "the tail must be zero-filled")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preallocating an already-complete file leaves its mtime alone`() {
        // The fast-resume interaction (PATCH-4): a restart whose files are already at full size
        // must not be seen as a modification, or every session would veto its own resume state.
        val base = Files.createTempDirectory("blaze-prealloc-idempotent")
        try {
            val file = file(64 * 1024, "movie.mkv")
            val torrent = torrent("single", file)
            val preallocating = storage(base, TorrentPreallocation.SPARSE)

            preallocating.getUnit(torrent, file).close()
            val location = base.resolve("movie.mkv")
            val mtime = Files.getLastModifiedTime(location)

            preallocating.getUnit(torrent, file).close()
            assertEquals(mtime, Files.getLastModifiedTime(location), "a second apply must not re-touch the file")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `normalised escape attempts are preallocated back inside the folder`() {
        val base = Files.createTempDirectory("blaze-prealloc-escapes")
        try {
            // `../..` folds to `_/_` exactly like torrentContentPath always did; preallocation has
            // to follow that same path and must not be what leaks a file out of the download folder.
            val file = file(4096, "..", "..", "etc", "passwd")
            storage(base, TorrentPreallocation.SPARSE).getUnit(torrent("hostile", file), file).close()

            assertTrue(Files.exists(base.resolve("_/_/etc/passwd")))
            assertFalse(Files.exists(base.parent.resolve("etc/passwd")), "the write escaped the download folder")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writing through a preallocated unit lands at the right offsets`() {
        val base = Files.createTempDirectory("blaze-prealloc-write")
        try {
            val size = 128 * 1024L
            val file = file(size, "movie.mkv")
            val unit: StorageUnit = storage(base, TorrentPreallocation.SPARSE).getUnit(torrent("single", file), file)
            try {
                assertEquals(5, unit.writeBlock(ByteBuffer.wrap("hello".toByteArray()), 4096L))
                // The untouched region must still read as zeros - the hole is logical content now.
                val head = ByteBuffer.allocate(8)
                unit.readBlockFully(head, 0L)
                assertTrue(head.array().all { it == 0.toByte() }, "the pre hole must read as zeros")
                val written = ByteBuffer.allocate(5)
                unit.readBlockFully(written, 4096L)
                assertEquals("hello", String(written.array()))
            } finally {
                unit.close()
            }
            assertEquals(size, Files.size(base.resolve("movie.mkv")))
            val raw = Files.readAllBytes(base.resolve("movie.mkv"))
            assertEquals("hello", raw.copyOfRange(4096, 4101).toString(Charsets.UTF_8))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an impossible download is rejected before any file is created`() {
        val base = Files.createTempDirectory("blaze-prealloc-nospace")
        try {
            val file = file(Long.MAX_VALUE, "huge.bin")
            try {
                storage(base, TorrentPreallocation.SPARSE).getUnit(torrent("single", file), file)
                fail("Long.MAX_VALUE bytes cannot fit on any volume")
            } catch (expected: InsufficientStorageException) {
                assertTrue(expected.message!!.contains("disk space"), "clear wording required: ${expected.message}")
            }
            assertFalse(Files.exists(base.resolve("huge.bin")), "a rejected preallocation must leave nothing behind")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    // ---- error surface ----

    @Test
    fun `a rejected preallocation deep in the cause chain surfaces as DiskFull`() {
        // What bt's session machinery actually throws: the storage failure wrapped in a
        // CompletionException (and sometimes an extra layer), not the raw exception.
        val wrapped = CompletionException(RuntimeException(InsufficientStorageException("no room")))
        assertSame(DownloadError.DiskFull, wrapped.toTorrentDownloadError())

        assertSame(DownloadError.DiskFull, InsufficientStorageException("no room").toTorrentDownloadError())
    }

    @Test
    fun `an ordinary failure keeps the historical network-failure mapping`() {
        val error = RuntimeException("peer reset").toTorrentDownloadError()
        assertEquals(DownloadError.NetworkFailure("peer reset"), error)
        assertEquals(DownloadError.NetworkFailure("Torrent error"), RuntimeException().toTorrentDownloadError())
    }

    // ---- helpers (metainfo stubs per TorrentContentLayoutTest's pattern) ----

    private fun storage(root: Path, mode: TorrentPreallocation) =
        TorrentContentStorage(root, preallocation = mode.toForkMode())

    private fun file(size: Long, vararg elements: String): TorrentFile = object : TorrentFile {
        override fun getSize(): Long = size
        override fun getPathElements(): List<String> = elements.toList()
    }

    private fun torrent(name: String, vararg files: TorrentFile): Torrent = object : Torrent {
        override fun getSource(): TorrentSource = throw UnsupportedOperationException()
        override fun getAnnounceKey(): Optional<AnnounceKey> = Optional.empty()
        override fun getTorrentId(): TorrentId = throw UnsupportedOperationException()
        override fun getName(): String = name
        override fun getChunkSize(): Long = 0
        override fun getChunkHashes(): Iterable<ByteArray> = emptyList()
        override fun getSize(): Long = files.sumOf { it.size }
        override fun getFiles(): List<TorrentFile> = files.toList()
        override fun isPrivate(): Boolean = false
        override fun getCreationDate(): Optional<Instant> = Optional.empty()
        override fun getCreatedBy(): Optional<String> = Optional.empty()
    }
}
