package org.blaze.engine.archive

/**
 * Abstractions for inspecting and selectively extracting a *remote* ZIP archive via HTTP byte-range
 * requests, without downloading the whole file.
 *
 * The design keeps format parsing decoupled from transport: [RemoteByteSource] is the only thing the
 * parser and extractor need — the ability to read a `[start, start+length)` window of a resource whose
 * total size is known. Production wires it to [org.blaze.engine.network.HttpNetworkClient.stream]; tests
 * wire it to an in-memory array. This is what lets "read the central directory" and "fetch one entry"
 * be unit-tested deterministically and reused for any ranged reader.
 */

/** A windowed reader over a remote resource. Reads are `[start, start + length)`, zero-based. */
interface RemoteByteSource {
    /** Total length of the resource, or null when the server would not say (unknown-length host). */
    suspend fun size(): Long?

    /**
     * Read up to [length] bytes beginning at [start]. Implementations must return exactly the bytes of
     * the window that exist; a truncated or range-ignoring server is surfaced here as an
     * [ArchiveAccessException] rather than a short read that could corrupt an entry.
     */
    suspend fun read(start: Long, length: Long): ByteArray

    /** True when the server proved it honours byte-range requests; false means selective reads fail. */
    val rangeSupported: Boolean
}

/** Raised for anything that makes the archive unusable: not a zip, corrupt, unsupported, out of range. */
class ArchiveAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A compression method Blaze can (or cannot) handle when extracting remotely. */
enum class ZipCompression { Stored, Deflate, Unsupported }

/**
 * One entry as recorded in the central directory. Offsets and sizes are the *64-bit* values resolved
 * from ZIP64 extras where the 32-bit fields were saturated, so large archives are measured honestly.
 */
data class ZipEntryInfo(
    /** The raw stored name; treated as untrusted and validated before any write. */
    val name: String,
    val isDirectory: Boolean,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val compression: ZipCompression,
    /** Offset of the entry's local file header. */
    val localHeaderOffset: Long,
    val crc32: Long,
    val encrypted: Boolean,
    val usesDataDescriptor: Boolean,
) {
    /**
     * Whether Blaze can retrieve this entry with a single ranged read. Encryption and unhandled
     * compression methods are excluded; a data descriptor (`flag` bit 3) is *not*, because the central
     * directory still carries the true compressed size, which is all a ranged read needs.
     */
    val extractable: Boolean
        get() = !encrypted && compression != ZipCompression.Unsupported

    val compressionRatio: Double?
        get() = if (compressedSize > 0) uncompressedSize.toDouble() / compressedSize else null
}

/**
 * The result of inspecting a remote archive: its entries and the assessment of whether selective
 * retrieval is possible at all. When [selectiveSupported] is false the caller must offer the
 * full-download fallback rather than silently fetching everything.
 */
data class ArchiveListing(
    val entries: List<ZipEntryInfo>,
    val totalUncompressedBytes: Long,
    val selectiveSupported: Boolean,
    val reasonIfUnsupported: String?,
) {
    fun entryNamed(name: String): ZipEntryInfo? = entries.firstOrNull { it.name == name }

    /** Entries under [folder] (a stored name ending in `/`), including the folder's own files. */
    fun entriesUnder(folder: String): List<ZipEntryInfo> =
        entries.filter { it.name.startsWith(folder) && !it.isDirectory }
}
