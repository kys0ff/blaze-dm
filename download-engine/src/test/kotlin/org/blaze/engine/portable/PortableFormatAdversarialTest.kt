package org.blaze.engine.portable

import java.nio.ByteBuffer
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
 * Adversarial review of the portable format (§26): deliberately break the on-disk artifact in the
 * ways a crash, a torn write or a hostile file could, and assert the reader always fails *safe* —
 * recovering a good slot or refusing the artifact outright — never trusting corrupt state or driving
 * an out-of-range allocation. Pure file logic: no network, no engine types.
 */
class PortableFormatAdversarialTest {

    private fun httpManifest(total: Long, done: Int, chunkCount: Int) = PortableManifest.Http(
        originalUrl = "https://example.com/file.bin",
        resolvedUrl = null,
        contentLength = total,
        chunkSize = 64 * 1024,
        chunkCount = chunkCount,
        acceptsRanges = true,
        validator = "\"etag-1\"",
        completedChunks = BitSet().apply { for (i in 0 until done) set(i) }
    )

    private fun writeBytes(path: Path, offset: Long, data: ByteArray) {
        Files.newByteChannel(path, StandardOpenOption.WRITE, StandardOpenOption.READ).use { ch ->
            ch.position(offset); ch.write(ByteBuffer.wrap(data))
        }
    }

    private fun slotHeader(magic: Int, generation: Long, payloadLen: Int, crc: Int) =
        ByteBuffer.allocate(PortableFormat.SLOT_HEADER_SIZE)
            .putInt(magic).putLong(generation).putInt(payloadLen).putInt(crc).array()

    private fun locatorBase(total: Long, slotSize: Long) = total + 2 * slotSize

    // ── Absurd metadata length ────────────────────────────────────────────────
    @Test
    fun `an absurd slot payload length is refused and the good slot is trusted`() = withTempDir { dir ->
        val total = 4L * 1024 * 1024
        val partial = dir.resolve("file.part")
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 5, 64))
        store.checkpoint(httpManifest(total, 40, 64)) // gen 2 in slot B

        // Overwrite slot A's header: valid magic, but a payload length larger than the whole slot.
        writeBytes(
            partial, total,
            slotHeader(PortableFormat.SLOT_MAGIC, 99L, (PortableFormat.HTTP_SLOT_SIZE + 1).toInt(), 0)
        )

        val artifact = assertNotNull(store.inspect())
        assertEquals(2L, artifact.generation, "the intact newer slot must win over the absurd one")
        assertEquals(40, (artifact.manifest as PortableManifest.Http).completedChunks.cardinality())
    }

    // ── Corrupted CRC of the newest slot (magic & length left intact) ──────────
    @Test
    fun `a bad CRC on the newest slot falls back to the previous generation`() = withTempDir { dir ->
        val total = 4L * 1024 * 1024
        val partial = dir.resolve("file.part")
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 5, 64))
        store.checkpoint(httpManifest(total, 40, 64)) // gen 2 in slot B

        // Flip a single payload byte in slot B (past the header) so only the CRC check fails.
        val slotB = total + PortableFormat.HTTP_SLOT_SIZE
        writeBytes(partial, slotB + PortableFormat.SLOT_HEADER_SIZE, byteArrayOf(0x01))

        val artifact = assertNotNull(store.inspect())
        assertEquals(1L, artifact.generation, "a CRC-invalid record must never be committed state")
        assertEquals(5, (artifact.manifest as PortableManifest.Http).completedChunks.cardinality())
    }

    // ── Mirrored locator resilience ───────────────────────────────────────────
    @Test
    fun `one torn locator mirror still locates the artifact via the other`() = withTempDir { dir ->
        val total = 1L * 1024 * 1024
        val partial = dir.resolve("file.part")
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 8, 16))

        // Corrupt only the preferred (second) mirror; leave the first intact.
        writeBytes(partial, locatorBase(total, PortableFormat.HTTP_SLOT_SIZE) + PortableFormat.LOCATOR_SIZE, ByteArray(8))

        val artifact = assertNotNull(store.inspect())
        assertEquals(total, artifact.contentLength)
        assertEquals(8, (artifact.manifest as PortableManifest.Http).completedChunks.cardinality())
    }

    @Test
    fun `a tampered locator fails its CRC and refuses the artifact`() = withTempDir { dir ->
        val total = 1L * 1024 * 1024
        val partial = dir.resolve("file.part")
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 8, 16))

        // Bump the version byte (offset 8 within each mirror) on BOTH mirrors without re-signing.
        val base = locatorBase(total, PortableFormat.HTTP_SLOT_SIZE)
        writeBytes(partial, base + 8, byteArrayOf(99))
        writeBytes(partial, base + PortableFormat.LOCATOR_SIZE + 8, byteArrayOf(99))

        // The self-validating CRC no longer matches, so the artifact is simply not portable.
        assertNull(store.inspect())
    }

    // ── Truncated / grown trailer ─────────────────────────────────────────────
    @Test
    fun `a truncated file whose length no longer matches the locator is refused`() = withTempDir { dir ->
        val total = 1L * 1024 * 1024
        val partial = dir.resolve("file.part")
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 1, 16))

        Files.newByteChannel(partial, StandardOpenOption.WRITE).use { it.truncate(Files.size(partial) - 1) }

        assertNull(store.inspect(), "locator length must match the file exactly")
    }

    // ── Self-healing after total metadata loss ────────────────────────────────
    @Test
    fun `checkpoint after both slots are lost restarts the generation clock`() = withTempDir { dir ->
        val total = 1L * 1024 * 1024
        val partial = dir.resolve("file.part")
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 3, 16))
        // Wipe both slots so nothing survives: the artifact becomes stale, not fatal.
        writeBytes(partial, total, ByteArray((2 * PortableFormat.HTTP_SLOT_SIZE).toInt()))
        assertTrue(assertNotNull(store.inspect()).stale)

        // A fresh checkpoint re-establishes a trustworthy generation-1 record.
        store.checkpoint(httpManifest(total, 12, 16))
        val artifact = assertNotNull(store.inspect())
        assertEquals(1L, artifact.generation)
        assertEquals(12, (artifact.manifest as PortableManifest.Http).completedChunks.cardinality())
    }

    // ── No huge allocation / write from a manifest that cannot fit ────────────
    @Test
    fun `a manifest larger than its slot is rejected instead of overflowing`() = withTempDir { dir ->
        val total = 4L * 1024 * 1024
        val partial = dir.resolve("file.part")
        assertFailsWith<PortableFormatException> {
            PortableStore(partial).create(PortableKind.HTTP, total, slotSize = 64L, httpManifest(total, 3, 64))
        }
    }

    // ── Finalization is a pure, idempotent shrink ─────────────────────────────
    @Test
    fun `finalize only ever shrinks and is idempotent`() = withTempDir { dir ->
        val content = ByteArray(9000) { (it % 253).toByte() }
        val total = content.size.toLong()
        val partial = dir.resolve("file.part")
        Files.write(partial, content)
        val store = PortableStore(partial)
        store.create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, httpManifest(total, 9, 9))
        store.checkpoint(httpManifest(total, 9, 9))

        store.finalize(total)
        store.finalize(total)                       // idempotent
        store.finalize(total + 10_000)              // must never grow the finished file

        assertEquals(total, Files.size(partial))
        assertContentEquals(content, Files.readAllBytes(partial))
        assertNull(store.inspect())
    }

    // ── Manifest decoder hostile-input ceilings ───────────────────────────────
    @Test
    fun `the manifest decoder rejects empty, unknown-kind and wrong-version records`() {
        assertFailsWith<PortableFormatException> { ManifestCodec.decode(ByteArray(0)) }
        assertFailsWith<PortableFormatException> { ManifestCodec.decode(byteArrayOf(99)) } // unknown kind
        assertFailsWith<PortableFormatException> {
            ManifestCodec.decode(byteArrayOf(0, 0, 0, 0, 2)) // kind HTTP, unsupported version 2
        }
    }

    @Test
    fun `a bitmap larger than the ceiling is refused without a huge allocation`() {
        // Craft an HTTP manifest whose trailing bitmap length exceeds MAX_BITMAP_BYTES but whose
        // buffer actually carries that many bytes, so the ceiling (not the truncation check) fires.
        val sink = ByteSink(64)
        sink.byte(PortableKind.HTTP.id); sink.i32(1)
        sink.string("https://example.com/f"); sink.string(null)
        sink.i64(1_000_000); sink.i64(65536); sink.i32(20)
        sink.byte(1); sink.string("\"v\"")
        val big = PortableFormat.MAX_BITMAP_BYTES + 1
        sink.i32(big)
        sink.bytes(ByteArray(big))
        assertFailsWith<PortableFormatException> { ManifestCodec.decode(sink.toByteArray()) }
    }

    private inline fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("blaze-portable-adversary")
        try {
            block(dir)
        } finally {
            runCatching { Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
