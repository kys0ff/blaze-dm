package org.blaze.engine.portable

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Self-describing container that lets a torrent download be resumed on a fresh machine without the
 * original `.torrent` file or magnet link.
 *
 * ## Why torrents use a marker file instead of an embedded region
 *
 * HTTP resumes from a *single* partial file, so its metadata rides in a trailer that is truncated
 * away on completion. A torrent is different on both counts, and the difference is fundamental
 * rather than incidental:
 *
 * - `FileSystemStorage` writes the torrent's *real* content files straight into a directory, exactly
 *   as the final layout must be. There is no `.part` to append a trailer to, and appending bytes to
 *   a content file would both break `bt`'s length/hash checks and violate the byte-for-byte rule.
 * - `bt` already treats "bytes exist on disk" as *unverified*: on restart it re-hashes every piece
 *   against the info-dictionary's piece hashes and only trusts pieces that match. So the piece
 *   bitmap does not have to be carried to be correct — only the metainfo it hashes against is
 *   genuinely irreplaceable.
 *
 * Therefore the portable torrent artifact is: the content directory (moved by the user) **plus one**
 * small marker file holding the canonical `.torrent` metainfo. Moving the torrent's destination
 * folder moves both together. On import the metainfo is handed back to `bt`, which re-verifies the
 * existing data and continues downloading only the missing/invalid pieces.
 *
 * ## Durability
 *
 * The marker's load-bearing payload — the metainfo — is immutable for the life of the download, and
 * the mutable part (a completed-piece *hint*, used only to size the import preview) is advisory
 * because `bt` re-derives the truth from hashes. A whole-file atomic replace (`temp` + rename in the
 * same directory) is therefore sufficient and is the standard crash-safe primitive on NTFS/ext4/APFS;
 * the double-buffered slot machinery in [PortableStore] exists for the HTTP region, which is
 * rewritten at high frequency *in place* next to concurrent worker writes. Every record still
 * carries a magic, version, generation and CRC32, and the manifest is decoded through the same
 * bounds-checking codec, so a hostile or half-written marker can never drive a bad write (§16).
 */
object TorrentPortableFile {

    private const val HEADER_SIZE = 8 + 2 + 2 + 8 + 4 + 4 // magic,ver,kind,gen,len,crc = 28
    private const val TORRENT_KIND_ID = 1 // == PortableKind.TORRENT.id

    /** Name of the marker, placed at the torrent destination root. Hidden-ish and unmistakable. */
    const val MARKER_NAME: String = ".blaze-portable"

    fun markerPath(destination: Path): Path = destination.resolve(MARKER_NAME)

    /**
     * Atomically writes [manifest]'s metainfo into the marker at [marker]. The caller must ensure
     * [marker]'s parent exists (it is the torrent destination, which the engine creates).
     */
    fun write(marker: Path, manifest: PortableManifest.Torrent, generation: Long) {
        val payload = ManifestCodec.encode(manifest)
        val buffer = java.nio.ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.put(PortableFormat.MAGIC)
        buffer.putShort(PortableFormat.FORMAT_VERSION.toShort())
        buffer.putShort(TORRENT_KIND_ID.toShort())
        buffer.putLong(generation)
        buffer.putInt(payload.size)
        val crcInput = java.nio.ByteBuffer.allocate(16 + payload.size)
        crcInput.putLong(generation)
        crcInput.putInt(payload.size)
        crcInput.put(payload)
        buffer.putInt(PortableFormat.crc32(crcInput.array(), 0, crcInput.position()))
        buffer.put(payload)
        buffer.flip()

        val tmp = marker.resolveSibling(marker.fileName.toString() + ".tmp")
        tmp.parent?.let { Files.createDirectories(it) }
        java.nio.channels.FileChannel.open(
            tmp,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
        ).use { ch ->
            ch.write(buffer)
            ch.force(true)
        }
        runCatching {
            Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            if (it is java.nio.file.AtomicMoveNotSupportedException ||
                it.cause is java.nio.file.AtomicMoveNotSupportedException
            ) {
                Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING)
            } else throw it
        }
    }

    /**
     * Reads and validates the marker, or returns null when [marker] is absent or is not a well-formed
     * Blaze torrent artifact. A null result is the caller's signal to fall back to the original
     * `.torrent`/magnet source rather than a hard failure.
     */
    fun read(marker: Path): TorrentManifestRecord? {
        val bytes = runCatching { Files.readAllBytes(marker) }.getOrNull() ?: return null
        if (bytes.size < HEADER_SIZE) return null
        val buf = java.nio.ByteBuffer.wrap(bytes)
        val magic = ByteArray(PortableFormat.MAGIC.size)
        buf.get(magic)
        if (!magic.contentEquals(PortableFormat.MAGIC)) return null
        if (buf.short.toInt() != PortableFormat.FORMAT_VERSION) return null
        if (buf.short.toInt() != TORRENT_KIND_ID) return null
        val generation = buf.long
        val len = buf.int
        val storedCrc = buf.int
        if (len < 0 || len > PortableFormat.MAX_MANIFEST_BYTES || len.toLong() + HEADER_SIZE > bytes.size) return null
        val payload = ByteArray(len)
        buf.get(payload)
        val crcInput = java.nio.ByteBuffer.allocate(16 + len)
        crcInput.putLong(generation)
        crcInput.putInt(len)
        crcInput.put(payload)
        if (PortableFormat.crc32(crcInput.array(), 0, crcInput.position()) != storedCrc) return null
        val manifest = runCatching { ManifestCodec.decode(payload) }.getOrNull() as? PortableManifest.Torrent
            ?: return null
        return TorrentManifestRecord(generation, manifest)
    }

    /** Removes the marker so a finished torrent folder is indistinguishable from a normal one. */
    fun delete(marker: Path) {
        runCatching { Files.deleteIfExists(marker) }
        runCatching { Files.deleteIfExists(marker.resolveSibling(marker.fileName.toString() + ".tmp")) }
    }
}

/** A validated marker: the [generation] it was written at and the embedded [manifest]. */
data class TorrentManifestRecord(val generation: Long, val manifest: PortableManifest.Torrent)
