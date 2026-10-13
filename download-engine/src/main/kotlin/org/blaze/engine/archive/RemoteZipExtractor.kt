package org.blaze.engine.archive

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Retrieves the byte ranges a *selected* set of ZIP entries occupies, inflates them, and writes them
 * under a confined destination — fetching nothing else from the archive.
 *
 * It is the retrieval counterpart to [ZipCentralDirectory]: the directory told us each entry's offset
 * and compressed size, so we can ask the source for exactly those windows. Because the entry names and
 * sizes all come from an untrusted remote archive, this class is where every security guarantee is
 * enforced: paths are rebuilt from scratch and cannot escape [destination], declared sizes are re-checked
 * against the resource, decompression output is capped against inflation bombs, and a CRC mismatch means
 * the entry is reported as failed rather than written half-corrupt.
 */
class RemoteZipExtractor(
    private val source: RemoteByteSource,
    /** Upper bound on a single entry's uncompressed bytes; guards the worst inflation bombs. */
    private val maxEntryUncompressedBytes: Long = 4L * 1024 * 1024 * 1024,
    /** Upper bound on the inflate ratio for a deflated entry, beyond which it is treated as a bomb. */
    private val maxCompressionRatio: Int = 1024,
) {
    private val logger = LoggerFactory.getLogger(RemoteZipExtractor::class.java)

    /** One entry that could not be retrieved, with a short reason safe to show the user. */
    data class Failure(val name: String, val reason: String)

    /**
     * Outcome of an extraction pass. [extracted] are the entries successfully written; [failures]
     * name those that could not be retrieved, so the caller can report exactly what did not land
     * instead of implying a partial extraction was complete.
     */
    data class Result(val extracted: List<String>, val failures: List<Failure>) {
        val allSucceeded: Boolean get() = failures.isEmpty()
    }

    /**
     * Extract [entries] into [destination], which is created if missing and treated as the confinement
     * root. Directory entries are materialised as folders; file entries are fetched, inflated, verified
     * and written. A failure of one entry never aborts the others.
     */
    suspend fun extract(entries: List<ZipEntryInfo>, destination: Path): Result {
        Files.createDirectories(destination)
        val root = destination.toAbsolutePath().normalize()
        val extracted = ArrayList<String>()
        val failures = ArrayList<Failure>()

        val archiveSize = source.size() ?: Long.MAX_VALUE
        for (entry in entries) {
            try {
                val safePath = safeResolve(root, entry.name)
                if (safePath == null) {
                    failures += Failure(entry.name, "unsafe entry path")
                    continue
                }
                if (entry.isDirectory) {
                    Files.createDirectories(safePath)
                    extracted += entry.name
                    continue
                }
                if (!entry.extractable) {
                    failures += Failure(entry.name, "entry is encrypted or uses an unsupported method")
                    continue
                }
                if (entry.compressedSize < 0 || entry.localHeaderOffset + entry.compressedSize > archiveSize) {
                    failures += Failure(entry.name, "entry range is out of bounds")
                    continue
                }
                if (entry.uncompressedSize > maxEntryUncompressedBytes) {
                    failures += Failure(entry.name, "entry declares an implausibly large size")
                    continue
                }

                val dataStart = resolveDataStart(entry)
                if (dataStart + entry.compressedSize > archiveSize) {
                    failures += Failure(entry.name, "entry data range is out of bounds")
                    continue
                }

                val compressed = source.read(dataStart, entry.compressedSize)
                val uncompressed = when (entry.compression) {
                    ZipCompression.Stored -> compressed
                    ZipCompression.Deflate -> inflate(compressed, entry)
                    ZipCompression.Unsupported -> error("unreachable: extractable already excludes this")
                }

                verifyCrc(entry, uncompressed)

                safePath.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(
                    safePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { it.write(uncompressed) }
                extracted += entry.name
            } catch (e: Exception) {
                logger.warn("Failed to extract archive entry '{}'", entry.name, e)
                failures += Failure(entry.name, e.message ?: e::class.simpleName ?: "extraction failed")
            }
        }
        return Result(extracted, failures)
    }

    /**
     * Locate where an entry's compressed data begins: past its *local* header, whose variable-length
     * name and extra fields differ from the central record. Reading 30 bytes is enough to reach the
     * two length fields, then the header's own extents tell us where the body starts.
     */
    private suspend fun resolveDataStart(entry: ZipEntryInfo): Long {
        val header = source.read(entry.localHeaderOffset, 30)
        if (header.size < 30 || readLe32(header, 0) != LOCAL_HEADER_SIGNATURE) {
            throw ArchiveAccessException("entry local header is missing or corrupt")
        }
        val nameLen = readLe16(header, 26).toLong()
        val extraLen = readLe16(header, 28).toLong()
        return entry.localHeaderOffset + 30 + nameLen + extraLen
    }

    private fun inflate(compressed: ByteArray, entry: ZipEntryInfo): ByteArray {
        val inflater = Inflater(/* nowrap */ true)
        inflater.setInput(compressed)
        val expected = entry.uncompressedSize
        if (expected > maxEntryUncompressedBytes) throw ArchiveAccessException("entry declares an implausibly large size")
        val cap = if (expected > 0) {
            expected
        } else {
            minOf(compressed.size.toLong() * maxCompressionRatio, maxEntryUncompressedBytes)
        }
        val boundedCap = minOf(cap, maxEntryUncompressedBytes)
        val out = java.io.ByteArrayOutputStream(
            minOf(boundedCap, 64L * 1024L * 1024L).toInt().coerceAtLeast(1024)
        )
        val buffer = ByteArray(64 * 1024)
        try {
            var total = 0L
            while (!inflater.finished()) {
                val read = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    throw ArchiveAccessException("entry data is not a valid deflate stream", e)
                }
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                total += read
                if (total > boundedCap) {
                    throw ArchiveAccessException("entry inflates beyond its declared size (compression bomb guard)")
                }
                out.write(buffer, 0, read)
            }
        } finally {
            inflater.end()
        }
        val data = out.toByteArray()
        if (entry.compressedSize > 0 &&
            data.size.toLong() > entry.compressedSize * maxCompressionRatio &&
            data.size > 1024 * 1024
        ) {
            throw ArchiveAccessException("entry compression ratio is implausible (compression bomb guard)")
        }
        return data
    }

    private fun verifyCrc(entry: ZipEntryInfo, data: ByteArray) {
        if (entry.crc32 == 0L && entry.uncompressedSize == 0L) return // empty entry, nothing to check
        val crc = CRC32()
        crc.update(data)
        if (crc.value != (entry.crc32 and 0xFFFFFFFFL)) {
            throw ArchiveAccessException("entry failed its checksum and may have changed since inspection")
        }
    }

    /**
     * Rebuild [name] as a path strictly beneath [root], or null when it is unsafe. An archive entry name
     * is attacker-controlled, so it is not simply joined: backslashes are normalised, and drive letters,
     * absolute paths, NUL bytes and `..` segments are all rejected outright rather than resolved.
     */
    internal fun safeResolve(root: Path, name: String): Path? {
        if (name.isEmpty() || name.indexOf('\u0000') >= 0) return null
        if (name.startsWith("/") || name.startsWith("\\")) return null
        if (DRIVE_LETTER.matches(name)) return null
        val segments = name.split('/', '\\').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        for (segment in segments) {
            if (segment == ".." || segment == ".") return null
            if (segment.indexOf('\u0000') >= 0) return null
        }
        var path = root
        for (segment in segments) path = path.resolve(segment)
        val normalized = path.normalize()
        return if (normalized == root || normalized.startsWith(root)) normalized else null
    }

    private companion object {
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
        private val DRIVE_LETTER = Regex("^[A-Za-z]:.*")

        private fun readLe16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun readLe32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or
                    ((b[at + 1].toLong() and 0xFF) shl 8) or
                    ((b[at + 2].toLong() and 0xFF) shl 16) or
                    ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}
