package org.blaze.engine.archive

import java.nio.charset.StandardCharsets

/**
 * Reads a ZIP archive's *central directory* out of a remote byte source using a handful of ranged
 * reads, without pulling the whole archive.
 *
 * A standard ZIP keeps its table of contents — entry names, sizes, compression methods and offsets to
 * each entry's local header — in a central directory near the *end* of the file, pointed at by the End
 * Of Central Directory record. That layout is exactly what makes selective retrieval possible: read the
 * tail, find the EOCD, read the directory, and you know which byte ranges each wanted entry occupies.
 *
 * Everything here treats the archive as untrusted: declared sizes and offsets are bounds-checked against
 * the real resource length, and entry counts / directory sizes are capped, so a hostile header cannot
 * drive an unbounded allocation. Only the 32-bit ZIP layout and its ZIP64 extensions are parsed; split
 * and encrypted archives are reported as unsupported rather than guessed at.
 */
object ZipCentralDirectory {

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD64_LOCATOR_SIGNATURE = 0x07064b50
    private const val EOCD64_SIGNATURE = 0x06064b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val ZIP64_EXTRA_ID = 0x0001

    private const val EOCD_MIN_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val EOCD_SEARCH_WINDOW = EOCD_MIN_SIZE + MAX_COMMENT

    /** Hard ceilings so an attacker-controlled header cannot ask Blaze to allocate wildly. */
    const val MAX_ENTRIES = 1_000_000
    const val MAX_CENTRAL_DIRECTORY_BYTES = 256L * 1024 * 1024

    /**
     * Inspect [source] and return its entry listing. Throws [ArchiveAccessException] for anything that
     * is not a well-formed, single-disk ZIP whose directory can be read from the given source.
     */
    suspend fun inspect(source: RemoteByteSource): ArchiveListing {
        val size = source.size()
            ?: throw ArchiveAccessException("server did not report a content length; cannot locate the central directory")
        if (size < EOCD_MIN_SIZE) throw ArchiveAccessException("resource is too small to be a ZIP archive")

        val eocd = findEocd(source, size)
        var entryCount = readLe16(eocd.bytes, eocd.index + 10).toLong() and 0xFFFF
        var cdSize = readLe32(eocd.bytes, eocd.index + 12)
        var cdOffset = readLe32(eocd.bytes, eocd.index + 16)
        val diskCount = readLe16(eocd.bytes, eocd.index + 4).toLong() and 0xFFFF
        val commentLength = readLe16(eocd.bytes, eocd.index + 20).toLong() and 0xFFFF

        // Comment length must reconcile with where the EOCD actually sits, or the "signature" we found
        // is really bytes inside some entry's data, not the record.
        val expectedEocdSize = EOCD_MIN_SIZE + commentLength
        if (eocd.index + expectedEocdSize > size) {
            throw ArchiveAccessException("corrupt end-of-central-directory record")
        }
        if (diskCount > 1) {
            throw ArchiveAccessException("multi-disk archives are not supported for selective retrieval")
        }

        // Saturated 32-bit fields mean the real numbers live in the ZIP64 records.
        if (entryCount == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val zip64 = readZip64Eocd(source, size, eocd.fileOffset)
            entryCount = zip64.first
            cdSize = zip64.second
            cdOffset = zip64.third
        }

        if (entryCount < 0 || entryCount > MAX_ENTRIES) {
            throw ArchiveAccessException("archive declares an implausible entry count: $entryCount")
        }
        if (cdSize < 0 || cdSize > MAX_CENTRAL_DIRECTORY_BYTES) {
            throw ArchiveAccessException("archive declares an implausible central directory size: $cdSize")
        }
        if (cdOffset < 0 || cdOffset + cdSize > size) {
            throw ArchiveAccessException("central directory extends beyond the archive")
        }

        val directory = source.read(cdOffset, cdSize)
        val entries = parseEntries(directory, entryCount.toInt(), size)

        val selectiveSupported = source.rangeSupported && entries.all { it.isDirectory || it.extractable }
        val unsupportedReason = when {
            !source.rangeSupported -> "the server does not support byte-range requests"
            entries.any { it.encrypted } -> "the archive contains encrypted entries"
            entries.any { it.compression == ZipCompression.Unsupported } ->
                "the archive uses an unsupported compression method"

            else -> null
        }

        return ArchiveListing(
            entries = entries,
            totalUncompressedBytes = entries.sumOf { it.uncompressedSize },
            selectiveSupported = selectiveSupported,
            reasonIfUnsupported = unsupportedReason,
        )
    }

    private class Eocd(val bytes: ByteArray, val index: Int, val fileOffset: Long)

    private suspend fun findEocd(source: RemoteByteSource, size: Long): Eocd {
        val window = minOf(size, EOCD_SEARCH_WINDOW.toLong())
        val start = size - window
        val tail = source.read(start, window)
        // Scan backwards: the EOCD is the last record, and a trailing comment can hide an earlier
        // signature, so the *highest* matching offset is the true one.
        var index = tail.size - EOCD_MIN_SIZE
        while (index >= 0) {
            if (readLe32(tail, index) == EOCD_SIGNATURE.toLong()) {
                return Eocd(tail, index, start + index)
            }
            index--
        }
        throw ArchiveAccessException("no end-of-central-directory record found; not a ZIP archive")
    }

    /** Returns (entryCount, cdSize, cdOffset) from the ZIP64 end-of-central-directory record. */
    private suspend fun readZip64Eocd(
        source: RemoteByteSource,
        size: Long,
        eocdFileOffset: Long
    ): Triple<Long, Long, Long> {
        val locatorOffset = eocdFileOffset - 20
        if (locatorOffset < 0) throw ArchiveAccessException("missing ZIP64 end-of-central-directory locator")
        val locator = source.read(locatorOffset, 20)
        if (readLe32(locator, 0) != EOCD64_LOCATOR_SIGNATURE.toLong()) {
            throw ArchiveAccessException("ZIP64 fields are saturated but no ZIP64 locator is present")
        }
        val zip64EocdOffset = readLe64(locator, 8)
        if (zip64EocdOffset < 0 || zip64EocdOffset + 56 > size) {
            throw ArchiveAccessException("ZIP64 end-of-central-directory offset is out of range")
        }
        val record = source.read(zip64EocdOffset, 56)
        if (readLe32(record, 0) != EOCD64_SIGNATURE.toLong()) {
            throw ArchiveAccessException("ZIP64 end-of-central-directory record is corrupt")
        }
        val entries = readLe64(record, 32)
        val cdSize = readLe64(record, 40)
        val cdOffset = readLe64(record, 48)
        return Triple(entries, cdSize, cdOffset)
    }

    private fun parseEntries(directory: ByteArray, declaredCount: Int, sourceSize: Long): List<ZipEntryInfo> {
        val entries = ArrayList<ZipEntryInfo>(declaredCount.coerceAtMost(1024))
        var pos = 0
        var read = 0
        while (pos + 46 <= directory.size && read < declaredCount) {
            if (readLe32(directory, pos) != CENTRAL_SIGNATURE.toLong()) break
            val flags = readLe16(directory, pos + 8).toLong() and 0xFFFF
            val method = readLe16(directory, pos + 10).toLong() and 0xFFFF
            val crc = readLe32(directory, pos + 16)
            var compSize = readLe32(directory, pos + 20)
            var uncompSize = readLe32(directory, pos + 24)
            val nameLen = readLe16(directory, pos + 28).toInt() and 0xFFFF
            val extraLen = readLe16(directory, pos + 30).toInt() and 0xFFFF
            val commentLen = readLe16(directory, pos + 32).toInt() and 0xFFFF
            var localOffset = readLe32(directory, pos + 42)

            val nameStart = pos + 46
            if (nameStart + nameLen > directory.size) break
            val name = String(directory, nameStart, nameLen, StandardCharsets.UTF_8)
            val extraStart = nameStart + nameLen
            val extra = if (extraLen > 0 && extraStart + extraLen <= directory.size) {
                directory.copyOfRange(extraStart, extraStart + extraLen)
            } else ByteArray(0)

            // A ZIP64 extra replaces the 0xFFFF-saturated sizes/offset with 64-bit values, always in the
            // fixed order uncompressed, compressed, local header offset, but only for those that were 0xFFFFFFFF.
            if (uncompSize == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                val z64 = readZip64Extra(
                    extra,
                    hadUncompressed = uncompSize == 0xFFFFFFFFL,
                    hadCompressed = compSize == 0xFFFFFFFFL,
                    hadOffset = localOffset == 0xFFFFFFFFL
                )
                if (z64.uncompressed != null) uncompSize = z64.uncompressed
                if (z64.compressed != null) compSize = z64.compressed
                if (z64.localOffset != null) localOffset = z64.localOffset
            }

            if (localOffset < 0 || localOffset >= sourceSize) {
                throw ArchiveAccessException("entry '$name' declares an offset beyond the archive")
            }

            entries += ZipEntryInfo(
                name = name,
                isDirectory = name.endsWith("/") || name.endsWith("\\"),
                compressedSize = compSize,
                uncompressedSize = uncompSize,
                compression = when (method) {
                    0L -> ZipCompression.Stored
                    8L -> ZipCompression.Deflate
                    else -> ZipCompression.Unsupported
                },
                localHeaderOffset = localOffset,
                crc32 = crc,
                encrypted = (flags and 0x1L) != 0L,
                usesDataDescriptor = (flags and 0x8L) != 0L,
            )
            pos = extraStart + extraLen + commentLen
            read++
        }
        if (entries.isEmpty()) throw ArchiveAccessException("central directory declares no entries")
        return entries
    }

    private class Zip64Extra(val uncompressed: Long?, val compressed: Long?, val localOffset: Long?)

    private fun readZip64Extra(
        extra: ByteArray,
        hadUncompressed: Boolean,
        hadCompressed: Boolean,
        hadOffset: Boolean
    ): Zip64Extra {
        var uncompressed: Long? = null
        var compressed: Long? = null
        var localOffset: Long? = null
        var pos = 0
        while (pos + 4 <= extra.size) {
            val id = readLe16(extra, pos).toInt() and 0xFFFF
            val len = readLe16(extra, pos + 2).toInt() and 0xFFFF
            val body = pos + 4
            if (body + len > extra.size) break
            if (id == ZIP64_EXTRA_ID) {
                var cursor = body
                if (hadUncompressed && cursor + 8 <= body + len) {
                    uncompressed = readLe64(extra, cursor); cursor += 8
                }
                if (hadCompressed && cursor + 8 <= body + len) {
                    compressed = readLe64(extra, cursor); cursor += 8
                }
                if (hadOffset && cursor + 8 <= body + len) {
                    localOffset = readLe64(extra, cursor); cursor += 8
                }
                break
            }
            pos = body + len
        }
        return Zip64Extra(uncompressed, compressed, localOffset)
    }

    // ---------------------------------------------------------------- little-endian readers

    private fun readLe16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
                ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or
                ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun readLe64(b: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }
}
