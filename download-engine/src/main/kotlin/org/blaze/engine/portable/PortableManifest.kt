package org.blaze.engine.portable

import java.util.BitSet

/** Which protocol a portable artifact carries. Persisted as a single discriminator byte. */
enum class PortableKind(val id: Int) {
    HTTP(0),
    TORRENT(1);

    companion object {
        fun fromId(id: Int): PortableKind = entries.firstOrNull { it.id == id }
            ?: throw PortableFormatException("unknown portable kind $id")
    }
}

/**
 * The decoded, validated resume manifest for one partial download.
 *
 * It is intentionally split per protocol so that each carries *only* the state genuinely needed
 * to reconstruct the transfer on a fresh machine — never secrets. A [Http] manifest is complete on
 * its own once the destination can re-probe the URL; a [Torrent] manifest embeds the canonical
 * `.torrent` metainfo so no original file or magnet is required.
 */
sealed interface PortableManifest {
    val kind: PortableKind
    val contentLength: Long

    /** Progress accounting for the import preview; number of content bytes already on disk. */
    val downloadedBytes: Long

    data class Http(
        val originalUrl: String,
        val resolvedUrl: String?,
        override val contentLength: Long,
        val chunkSize: Long,
        val chunkCount: Int,
        val acceptsRanges: Boolean,
        val validator: String?,
        val completedChunks: BitSet
    ) : PortableManifest {
        override val kind get() = PortableKind.HTTP
        override val downloadedBytes: Long
            get() = minOf(chunkCount.toLong(), completedChunks.cardinality().toLong())
                .let { done -> if (done <= 0) 0L else minOf(contentLength, done * chunkSize) }
    }

    data class Torrent(
        val name: String,
        /** Full canonical `.torrent` bytes (info dict + announce), the library's own representation. */
        val torrentFile: ByteArray,
        override val contentLength: Long,
        val pieceLength: Long,
        val pieceCount: Int,
        val trackers: List<String>,
        val files: List<PortableTorrentFile>,
        /** Completed *verified* pieces; treated as a progress hint, always re-checked on resume. */
        val completedPieces: BitSet,
        val multiFile: Boolean
    ) : PortableManifest {
        override val kind get() = PortableKind.TORRENT
        override val downloadedBytes: Long
            get() = if (pieceLength <= 0) 0L
            else minOf(pieceCount.toLong(), completedPieces.cardinality().toLong()) * pieceLength

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Torrent) return false
            return name == other.name && torrentFile.contentEquals(other.torrentFile) &&
                contentLength == other.contentLength && pieceLength == other.pieceLength &&
                pieceCount == other.pieceCount && trackers == other.trackers && files == other.files &&
                completedPieces == other.completedPieces && multiFile == other.multiFile
        }

        override fun hashCode(): Int {
            var r = name.hashCode()
            r = 31 * r + torrentFile.contentHashCode()
            r = 31 * r + contentLength.hashCode()
            r = 31 * r + pieceLength.hashCode()
            r = 31 * r + pieceCount
            r = 31 * r + trackers.hashCode()
            r = 31 * r + files.hashCode()
            r = 31 * r + completedPieces.hashCode()
            r = 31 * r + multiFile.hashCode()
            return r
        }
    }
}

/** One file inside a torrent. [relativePath] is always forward-slash separated and validated. */
data class PortableTorrentFile(val relativePath: String, val size: Long)

private const val MANIFEST_VERSION = 1

internal object ManifestCodec {
    fun encode(manifest: PortableManifest): ByteArray {
        val sink = ByteSink(if (manifest is PortableManifest.Http) 512 else 4096)
        sink.byte(manifest.kind.id)
        sink.i32(MANIFEST_VERSION)
        when (manifest) {
            is PortableManifest.Http -> encodeHttp(sink, manifest)
            is PortableManifest.Torrent -> encodeTorrent(sink, manifest)
        }
        val out = sink.toByteArray()
        if (out.size > PortableFormat.MAX_MANIFEST_BYTES) {
            throw PortableFormatException("manifest too large: ${out.size} bytes")
        }
        return out
    }

    private fun encodeHttp(sink: ByteSink, m: PortableManifest.Http) {
        sink.string(m.originalUrl)
        sink.string(m.resolvedUrl)
        sink.i64(m.contentLength)
        sink.i64(m.chunkSize)
        sink.i32(m.chunkCount)
        sink.byte(if (m.acceptsRanges) 1 else 0)
        sink.string(m.validator)
        sink.blob(m.completedChunks.toByteArray())
    }

    private fun encodeTorrent(sink: ByteSink, m: PortableManifest.Torrent) {
        sink.string(m.name)
        sink.blob(m.torrentFile)
        sink.i64(m.contentLength)
        sink.i64(m.pieceLength)
        sink.i32(m.pieceCount)
        sink.i32(m.trackers.size)
        m.trackers.forEach { sink.string(it) }
        sink.byte(if (m.multiFile) 1 else 0)
        sink.i32(m.files.size)
        m.files.forEach { sink.string(it.relativePath); sink.i64(it.size) }
        sink.blob(m.completedPieces.toByteArray())
    }

    fun decode(bytes: ByteArray): PortableManifest {
        if (bytes.isEmpty()) throw PortableFormatException("empty manifest")
        val src = ByteSource(bytes)
        val kind = PortableKind.fromId(src.byte())
        val version = src.i32()
        if (version != MANIFEST_VERSION) throw PortableFormatException("unsupported manifest version $version")
        return when (kind) {
            PortableKind.HTTP -> decodeHttp(src)
            PortableKind.TORRENT -> decodeTorrent(src)
        }
    }

    private fun decodeHttp(src: ByteSource): PortableManifest.Http {
        val url = src.string() ?: throw PortableFormatException("http manifest missing url")
        val resolved = src.string()
        val total = src.i64()
        val chunkSize = src.i64()
        val chunkCount = src.i32()
        val ranges = src.byte() == 1
        val validator = src.string()
        val bits = BitSet.valueOf(readBoundedBitmap(src))
        if (total <= 0) throw PortableFormatException("non-positive content length $total")
        if (chunkSize <= 0) throw PortableFormatException("non-positive chunk size $chunkSize")
        if (chunkCount < 0 || chunkCount > HttpPlanLimits.MAX_CHUNKS) {
            throw PortableFormatException("chunk count $chunkCount out of range")
        }
        if (bits.length() > chunkCount) throw PortableFormatException("bitmap describes more chunks than the plan")
        return PortableManifest.Http(url, resolved, total, chunkSize, chunkCount, ranges, validator, bits)
    }

    private fun decodeTorrent(src: ByteSource): PortableManifest.Torrent {
        val name = src.string() ?: throw PortableFormatException("torrent manifest missing name")
        val torrentFile = src.blob()
        if (torrentFile.isEmpty()) throw PortableFormatException("torrent manifest missing metainfo")
        if (torrentFile.size > PortableFormat.MAX_MANIFEST_BYTES) throw PortableFormatException("metainfo too large")
        val total = src.i64()
        val pieceLength = src.i64()
        val pieceCount = src.i32()
        val trackerCount = src.i32()
        if (trackerCount < 0 || trackerCount > PortableFormat.MAX_TRACKERS) {
            throw PortableFormatException("tracker count $trackerCount out of range")
        }
        val trackers = List(trackerCount) { src.string().orEmpty() }
        val multiFile = src.byte() == 1
        val fileCount = src.i32()
        if (fileCount < 0 || fileCount > PortableFormat.MAX_FILES) {
            throw PortableFormatException("file count $fileCount out of range")
        }
        val files = List(fileCount) {
            val rel = src.string() ?: throw PortableFormatException("file entry missing path")
            PortableTorrentFile(safeRelativePath(rel), src.i64())
        }
        val pieces = BitSet.valueOf(readBoundedBitmap(src))
        if (total <= 0) throw PortableFormatException("non-positive torrent length $total")
        if (pieceCount < 0) throw PortableFormatException("negative piece count")
        return PortableManifest.Torrent(name, torrentFile, total, pieceLength, pieceCount, trackers, files, pieces, multiFile)
    }

    private fun readBoundedBitmap(src: ByteSource): ByteArray {
        val bytes = src.blob()
        if (bytes.size > PortableFormat.MAX_BITMAP_BYTES) {
            throw PortableFormatException("bitmap too large: ${bytes.size}")
        }
        return bytes
    }

    /**
     * Rejects absolute paths, drive letters, parent traversal and NULs so a hostile torrent cannot
     * make the destination write outside the chosen folder (§16).
     */
    fun safeRelativePath(raw: String): String {
        val normalized = raw.replace('\\', '/')
        if (normalized.isBlank()) throw PortableFormatException("empty file path")
        if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            throw PortableFormatException("absolute path in metadata: $raw")
        }
        if (normalized.indexOf('\u0000') >= 0) throw PortableFormatException("NUL in path")
        for (segment in normalized.split('/')) {
            if (segment == "..") throw PortableFormatException("parent traversal in path: $raw")
        }
        return normalized
    }
}

/** Mirrors the engine's chunk-count ceiling so a decoded plan can never be absurd. */
private object HttpPlanLimits {
    const val MAX_CHUNKS = 20_000
}
