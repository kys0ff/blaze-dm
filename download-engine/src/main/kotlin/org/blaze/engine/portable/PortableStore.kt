package org.blaze.engine.portable

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Result of inspecting a file for an embedded portable artifact.
 *
 * [contentLength] and [kind] come from the (self-validating) locator; [manifest] is the newest
 * intact metadata record recovered from the double-buffered slots. A [stale] manifest means the
 * slots held no usable record even though the locator was valid — the content region can still be
 * reused as a contiguous prefix, so this is a soft, resumable condition rather than "not portable".
 */
data class PortableArtifact(
    val kind: PortableKind,
    val contentLength: Long,
    val slotSize: Long,
    val generation: Long,
    val manifest: PortableManifest?,
    val stale: Boolean
)

/**
 * Reads and writes the crash-safe portable region of a partial download.
 *
 * All operations open their own [FileChannel] with `WRITE`; positioned writes make this safe to run
 * alongside the transfer workers, whose content writes never target `[N, fileEnd)` (§ layout). Only
 * [finalize] shrinks the file, and it is called strictly after every worker has stopped and the
 * content has been forced.
 *
 * Durability model (the invariants every caller relies on):
 * 1. A record is committed by writing it into the *inactive* slot and forcing; the previous
 *    generation stays intact in the other slot the whole time.
 * 2. Recovery reads both slots and trusts only records whose magic *and* CRC validate, choosing the
 *    highest generation. A torn write fails CRC and is ignored, so no partial record is ever read.
 * 3. The locator is static and mirrored, so it cannot be caught mid-update; one valid mirror is
 *    enough to locate everything else.
 */
class PortableStore(private val path: Path) {

    /**
     * Lays out the metadata region and writes the initial [manifest] (generation 1). The content
     * region up to `contentLength` must already be reserved by the caller.
     */
    fun create(kind: PortableKind, contentLength: Long, slotSize: Long, manifest: PortableManifest) {
        require(contentLength > 0) { "portable artifact needs a known content length" }
        val total = PortableFormat.partialLength(contentLength, slotSize)
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { ch ->
            // Positional writes to the locator at the tail grow the file to `total`; the bytes
            // between the content and the slots stay sparse holes, so this costs metadata, not I/O.
            if (ch.size() > total) ch.truncate(total)
            val payload = ManifestCodec.encode(manifest)
            writeSlot(ch, contentLength, slotSize, index = 0, generation = 1, payload = payload)
            val locator = encodeLocator(kind, contentLength, slotSize, System.currentTimeMillis())
            ch.write(locator.duplicate(), contentLength + 2 * slotSize)
            ch.write(locator.duplicate(), contentLength + 2 * slotSize + PortableFormat.LOCATOR_SIZE)
            ch.force(true)
        }
    }

    /**
     * Writes [manifest] as a new generation into the slot that does not currently hold the newest
     * record, then forces. Cost scales with the manifest (a few KiB for HTTP), never the content.
     */
    fun checkpoint(manifest: PortableManifest) {
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.READ).use { ch ->
            val locator = readValidLocator(ch) ?: return@use
            val (contentLength, slotSize) = locator.contentLength to locator.slotSize
            val active = readActiveSlot(ch, contentLength, slotSize)
            // Alternate: overwrite the slot that is *not* the current winner. With no valid record
            // yet (both corrupt/absent) fall back to slot A and restart the generation clock.
            val target = if (active.index < 0) 0 else 1 - active.index
            val nextGeneration = if (active.index < 0) 1 else active.generation + 1
            writeSlot(ch, contentLength, slotSize, index = target, generation = nextGeneration, payload = ManifestCodec.encode(manifest))
            ch.force(true)
        }
    }

    /**
     * Safely strips the portable region, leaving exactly [contentLength] bytes.
     *
     * Called only after the content has been verified and force-flushed. Truncation is a metadata
     * operation on every target filesystem, and the trailing region is discarded data, so the only
     * crash window this opens is "size already N, DB not yet marked complete" — which the resume path
     * resolves idempotently. (Re-fetching a complete range yields the same bytes.)
     */
    fun finalize(contentLength: Long) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { ch ->
            ch.force(true)
            if (ch.size() > contentLength) {
                ch.truncate(contentLength)
                ch.force(true)
            }
        }
    }

    /** Returns the recovered artifact, or null when [path] is not a (partial) portable artifact. */
    fun inspect(): PortableArtifact? = FileChannel.open(path, StandardOpenOption.READ).use { ch ->
        readArtifact(ch)
    }

    private fun readArtifact(ch: FileChannel): PortableArtifact? {
        val locator = readValidLocator(ch) ?: return null
        val (contentLength, slotSize, kind) = Triple(locator.contentLength, locator.slotSize, locator.kind)
        val active = readActiveSlot(ch, contentLength, slotSize)
        val payload = active.payload ?: return PortableArtifact(kind, contentLength, slotSize, active.generation, manifest = null, stale = true)
        val manifest = runCatching { ManifestCodec.decode(payload) }.getOrNull()
            ?: return PortableArtifact(kind, contentLength, slotSize, active.generation, manifest = null, stale = true)
        return PortableArtifact(kind, contentLength, slotSize, active.generation, manifest, stale = false)
    }

    // ------------------------------------------------------------------ slots

    private class ActiveRecord(val generation: Long, val payload: ByteArray?, val index: Int) {
        companion object {
            val NONE = ActiveRecord(0L, null, -1)
        }
    }

    private fun slotOffset(contentLength: Long, slotSize: Long, index: Int): Long =
        contentLength + index * slotSize

    private fun writeSlot(
        ch: FileChannel,
        contentLength: Long,
        slotSize: Long,
        index: Int,
        generation: Long,
        payload: ByteArray
    ) {
        val header = PortableFormat.SLOT_HEADER_SIZE
        if (payload.size.toLong() + header > slotSize) {
            throw PortableFormatException("manifest (${payload.size}B) does not fit a ${slotSize}B slot")
        }
        // Only the header + payload are written. The record is self-delimiting (payload length is
        // stored), so any stale bytes the slot previously held beyond this record are never read,
        // and we avoid re-writing the whole reserved slot on every checkpoint (write amplification).
        val record = ByteBuffer.allocate(header + payload.size)
        record.putInt(PortableFormat.SLOT_MAGIC)
        record.putLong(generation)
        record.putInt(payload.size)
        val crcInput = ByteBuffer.allocate(12 + payload.size)
        crcInput.putLong(generation)
        crcInput.putInt(payload.size)
        crcInput.put(payload)
        record.putInt(PortableFormat.crc32(crcInput.array(), 0, crcInput.position()))
        record.put(payload)
        record.flip()
        ch.write(record, slotOffset(contentLength, slotSize, index))
    }

    private fun readSlot(ch: FileChannel, contentLength: Long, slotSize: Long, index: Int): Pair<Long, ByteArray?> {
        val base = slotOffset(contentLength, slotSize, index)
        val header = ByteBuffer.allocate(PortableFormat.SLOT_HEADER_SIZE)
        if (!readFully(ch, header, base)) return -1L to null
        header.flip()
        if (header.int != PortableFormat.SLOT_MAGIC) return -1L to null
        val generation = header.long
        val payloadLen = header.int
        val storedCrc = header.int
        if (payloadLen < 0 || payloadLen.toLong() + PortableFormat.SLOT_HEADER_SIZE > slotSize) return -1L to null
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0 && !readFully(ch, ByteBuffer.wrap(payload), base + PortableFormat.SLOT_HEADER_SIZE)) return -1L to null
        val crcInput = ByteBuffer.allocate(12 + payloadLen)
        crcInput.putLong(generation)
        crcInput.putInt(payloadLen)
        crcInput.put(payload)
        val actual = PortableFormat.crc32(crcInput.array(), 0, crcInput.position())
        return if (actual == storedCrc) generation to payload else -1L to null
    }

    /** Reads until [buf] is full or the channel stops yielding bytes. */
    private fun readFully(ch: FileChannel, buf: ByteBuffer, position: Long): Boolean {
        var read = 0
        while (buf.hasRemaining()) {
            val n = ch.read(buf, position + read)
            if (n <= 0) break
            read += n
        }
        return !buf.hasRemaining()
    }

    /** Reads both slots and returns the winning (highest valid generation) record and its slot. */
    private fun readActiveSlot(ch: FileChannel, contentLength: Long, slotSize: Long): ActiveRecord {
        val (genA, payA) = readSlot(ch, contentLength, slotSize, 0)
        val (genB, payB) = readSlot(ch, contentLength, slotSize, 1)
        return when {
            payA == null && payB == null -> ActiveRecord.NONE
            payB == null -> ActiveRecord(genA, payA, 0)
            payA == null -> ActiveRecord(genB, payB, 1)
            genB > genA -> ActiveRecord(genB, payB, 1)
            else -> ActiveRecord(genA, payA, 0)
        }
    }

    // ------------------------------------------------------------------ locator

    private class Locator(val kind: PortableKind, val contentLength: Long, val slotSize: Long)

    private fun encodeLocator(kind: PortableKind, contentLength: Long, slotSize: Long, createdAt: Long): ByteBuffer {
        val buf = ByteBuffer.allocate(PortableFormat.LOCATOR_SIZE)
        buf.put(PortableFormat.MAGIC)
        buf.put(PortableFormat.FORMAT_VERSION.toByte())
        buf.put(kind.id.toByte())
        buf.putShort(0) // reserved
        buf.putLong(contentLength)
        buf.putLong(slotSize)
        buf.putLong(createdAt)
        while (buf.position() < PortableFormat.LOCATOR_SIZE - 4) buf.put(0)
        val crc = PortableFormat.crc32(buf.array(), 0, PortableFormat.LOCATOR_SIZE - 4)
        buf.putInt(crc)
        buf.flip()
        return buf
    }

    private fun decodeLocator(bytes: ByteArray, fileSize: Long): Locator? {
        if (bytes.size != PortableFormat.LOCATOR_SIZE) return null
        val buf = ByteBuffer.wrap(bytes)
        val magic = ByteArray(PortableFormat.MAGIC.size)
        buf.get(magic)
        if (!magic.contentEquals(PortableFormat.MAGIC)) return null
        if (PortableFormat.crc32(bytes, 0, PortableFormat.LOCATOR_SIZE - 4) != buf.getInt(PortableFormat.LOCATOR_SIZE - 4)) return null
        if (buf.get(8).toInt() != PortableFormat.FORMAT_VERSION) return null
        val kind = runCatching { PortableKind.fromId(buf.get(9).toInt()) }.getOrNull() ?: return null
        val contentLength = buf.getLong(10 + 2)
        val slotSize = buf.getLong(20)
        if (contentLength <= 0 || slotSize <= 0) return null
        if (PortableFormat.partialLength(contentLength, slotSize) != fileSize) return null
        return Locator(kind, contentLength, slotSize)
    }

    /** Reads whichever locator mirror validates. */
    private fun readValidLocator(ch: FileChannel): Locator? {
        val size = ch.size()
        if (size < PortableFormat.regionSize(1) ) return null
        val locatorStart = size - 2L * PortableFormat.LOCATOR_SIZE
        val buf = ByteBuffer.allocate(PortableFormat.LOCATOR_SIZE)
        // Mirror 1 (written second at create; prefer it if both valid).
        ch.read(buf, locatorStart + PortableFormat.LOCATOR_SIZE)
        buf.flip()
        decodeLocator(buf.array(), size)?.let { return it }
        buf.clear()
        ch.read(buf, locatorStart)
        buf.flip()
        return decodeLocator(buf.array(), size)
    }
}

/** Top-level detection helper used by the import flow: is [path] a resumable portable artifact? */
fun inspectPortableFile(path: Path): PortableArtifact? =
    runCatching { PortableStore(path).inspect() }.getOrNull()
