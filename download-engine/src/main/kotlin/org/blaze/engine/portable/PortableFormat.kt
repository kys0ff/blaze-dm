package org.blaze.engine.portable

import java.util.zip.CRC32

/**
 * Constants and primitive codecs for the self-contained portable partial-download format.
 *
 * ## Why this layout
 *
 * A partial download must be resumable *from the file alone* after it is moved to another
 * machine, yet the finished file has to be byte-for-byte the original resource. Those two
 * requirements are reconciled by keeping the metadata in a private region that sits **after**
 * the content and is truncated away on completion:
 *
 * ```text
 * [0 .. N-1]                 content region      (N = expected final length)
 * [N .. N+2*SLOT-1]          metadata slots A|B  (double-buffered, crash-safe)
 * [fileEnd-2*LOCATOR .. )    mirrored locators   (self-validating, static after creation)
 * ```
 *
 * Invariants that make the format safe:
 * - Content writes always target `[0, N)`; metadata writes always target `[N, fileEnd)`. The two
 *   never overlap, so a worker and the metadata writer can share the file without coordination.
 * - The locator is written **once** at creation and never mutated, so it cannot be caught half
 *   rewritten by a crash; it is mirrored twice so a single torn block still leaves a valid copy.
 * - Metadata updates alternate between slot A and slot B. A commit only ever overwrites the
 *   *inactive* slot, so the previous good generation survives until the new record is fully
 *   written and validated by its CRC. A crash mid-update leaves the in-progress slot invalid and
 *   recovery transparently falls back to the other slot.
 * - Every record carries a magic and a CRC32, and every length is bounds-checked on read, so a
 *   hostile or corrupted artifact can never drive an out-of-range allocation or write.
 */
internal object PortableFormat {
    /** 8-byte file signature at the start of each locator; "BLAZEPRT". */
    val MAGIC: ByteArray = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'A'.code.toByte(), 'Z'.code.toByte(),
        'E'.code.toByte(), 'P'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte())

    const val FORMAT_VERSION: Int = 1

    /** Slot signature: "BPS1" (Blaze Portable Slot v1). */
    const val SLOT_MAGIC: Int = 0x4250_5331

    /** Bytes reserved per metadata slot (HTTP uses a fraction; torrent metainfo can be large). */
    const val HTTP_SLOT_SIZE: Long = 128L * 1024
    const val TORRENT_SLOT_SIZE: Long = 8L * 1024 * 1024

    /** Fixed size of one locator mirror. */
    const val LOCATOR_SIZE: Int = 64

    /** Fixed size of the per-slot record header that precedes the manifest payload. */
    const val SLOT_HEADER_SIZE: Int = 20

    // Defensive ceilings so a malformed artifact cannot trigger huge allocations (§16).
    const val MAX_STRING_BYTES = 64 * 1024
    const val MAX_BITMAP_BYTES = 1024 * 1024
    const val MAX_TRACKERS = 4096
    const val MAX_FILES = 1_000_000
    const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024

    /** Total metadata overhead a partial file carries beyond its content: two slots + two locators. */
    fun regionSize(slotSize: Long): Long = 2 * slotSize + 2 * LOCATOR_SIZE

    /** File length while a download is incomplete. */
    fun partialLength(contentLength: Long, slotSize: Long): Long = contentLength + regionSize(slotSize)

    fun crc32(data: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }
}

/**
 * Append-only big-endian writer used to serialize manifests, slots and locators. Grows its own
 * buffer so callers never have to pre-compute exact sizes.
 */
internal class ByteSink(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(32))
    private var pos = 0

    private fun ensure(extra: Int) {
        if (pos + extra <= buf.size) return
        var cap = buf.size * 2
        while (cap < pos + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun byte(v: Int): ByteSink { ensure(1); buf[pos++] = v.toByte(); return this }
    fun i16(v: Int): ByteSink { ensure(2); buf[pos++] = (v ushr 8).toByte(); buf[pos++] = v.toByte(); return this }
    fun i32(v: Int): ByteSink {
        ensure(4)
        buf[pos++] = (v ushr 24).toByte(); buf[pos++] = (v ushr 16).toByte()
        buf[pos++] = (v ushr 8).toByte(); buf[pos++] = v.toByte()
        return this
    }
    fun i64(v: Long): ByteSink {
        ensure(8)
        for (shift in 56 downTo 0 step 8) buf[pos++] = (v ushr shift).toByte()
        return this
    }

    fun bytes(v: ByteArray): ByteSink { ensure(v.size); v.copyInto(buf, pos); pos += v.size; return this }

    /** Length-prefixed UTF-8 string; a negative length encodes null. */
    fun string(v: String?): ByteSink {
        if (v == null) return i32(-1)
        val encoded = v.toByteArray(Charsets.UTF_8)
        i32(encoded.size)
        return bytes(encoded)
    }

    /** Length-prefixed opaque byte array; -1 encodes null. */
    fun blob(v: ByteArray?): ByteSink {
        if (v == null) return i32(-1)
        i32(v.size)
        return bytes(v)
    }

    fun toByteArray(): ByteArray = buf.copyOf(pos)
    val size: Int get() = pos
}

/** Bounds-checked big-endian reader; every accessor throws [PortableFormatException] on overrun. */
internal class ByteSource(private val buf: ByteArray, start: Int = 0) {
    private var pos = start

    private fun need(n: Int) {
        if (n < 0 || pos + n > buf.size) throw PortableFormatException("metadata truncated at byte $pos (need $n)")
    }

    fun byte(): Int { need(1); return buf[pos++].toInt() and 0xFF }
    fun i16(): Int { need(2); val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF); pos += 2; return v }
    fun i32(): Int {
        need(4)
        val v = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }
    fun i64(): Long {
        need(8)
        var v = 0L
        repeat(8) { v = (v shl 8) or (buf[pos + it].toInt() and 0xFF).toLong() }
        pos += 8
        return v
    }

    fun blob(): ByteArray {
        val len = i32()
        if (len < 0) throw PortableFormatException("negative blob length")
        need(len)
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun string(): String? {
        val len = i32()
        if (len == -1) return null
        if (len < 0 || len > PortableFormat.MAX_STRING_BYTES) throw PortableFormatException("string length $len out of bounds")
        need(len)
        val out = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return out
    }

    val remaining: Int get() = buf.size - pos
}

/** Raised for any structurally invalid or hostile portable record. Never a hard failure upstream. */
class PortableFormatException(message: String) : Exception(message)
