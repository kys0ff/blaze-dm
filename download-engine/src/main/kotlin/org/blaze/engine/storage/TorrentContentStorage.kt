package org.blaze.engine.storage

import bt.data.Storage
import bt.data.StorageUnit
import bt.data.file.FileCacheKey
import bt.data.file.OpenFileCache
import bt.metainfo.Torrent
import bt.metainfo.TorrentFile
import bt.net.buffer.ByteBufferView
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Decides where a torrent's bytes land on disk: exactly inside the download's own folder.
 *
 * `bt`'s [bt.data.file.FileSystemStorage] roots a multi-file torrent at
 * `<the directory it was given>/<name from the metainfo>`, because it assumes it was handed a
 * *downloads directory* shared between torrents. Blaze means the opposite by that parameter: the
 * destination of a [org.blaze.engine.api.DownloadRequest.Torrent] already **is** the torrent's
 * folder - the service derived its name from the source, the portable marker is written into it,
 * and the UI opens it to reveal the download. Feeding one into the other produced
 * `~/Downloads/Big.Buck/Big.Buck/…`: one level from Blaze, a second from the library, and the extra
 * level kept the metainfo's own name even after the user renamed the download.
 *
 * So Blaze owns the layout here. The metainfo's root name is never consulted - each file's path
 * elements are resolved under the destination, and nothing is allowed to escape it. Everything that
 * is genuinely the library's business - the bounded set of open channels, the locking around them,
 * the flushing - is delegated to `bt`'s own [OpenFileCache] instead of being reinvented. Even the
 * naming follows the library's rules: [torrentContentPath] reproduces them exactly, so a download
 * already on disk is still found under the name it was written with.
 */
internal class TorrentContentStorage(
    private val root: Path,
    maxOpenFiles: Int = DEFAULT_MAX_OPEN_FILES,
) : Storage {

    private val cache = OpenFileCache(maxOpenFiles)

    // The metainfo's `torrent.name` is deliberately not consulted: it is the library's cue for the
    // root folder of a multi-file torrent, and Blaze has already chosen that folder - under the name
    // the user sees, which may well differ once a magnet's `dn` or a rename has had its say.
    override fun getUnit(torrent: Torrent, torrentFile: TorrentFile): StorageUnit =
        CachedFileUnit(cache, FileCacheKey(torrentContentPath(root, torrentFile.pathElements), torrentFile.size))

    override fun flush() = cache.flush()

    private companion object {
        /** The ceiling `bt`'s own file storage uses - ample for a many-file torrent. */
        const val DEFAULT_MAX_OPEN_FILES = 256
    }
}

/**
 * One torrent file, read and written through the shared cache of open channels.
 *
 * Deliberately thin, and deliberately identical to the library's unit in the one place that affects
 * correctness: a block that was never written answers `-1` rather than zeroes, so `bt` keeps
 * treating absent bytes as unverified instead of hashing a sparse hole it did not download.
 */
private class CachedFileUnit(
    private val cache: OpenFileCache,
    private val key: FileCacheKey,
) : StorageUnit {

    override fun readBlock(buffer: ByteBuffer, offset: Long): Int =
        if (!cache.existsOnFileSystem(key)) -1 else cache.readBlock(key, buffer, offset)

    override fun readBlockFully(buffer: ByteBuffer, offset: Long) = cache.readBlockFully(key, buffer, offset)

    override fun writeBlock(buffer: ByteBuffer, offset: Long): Int = cache.writeBlock(key, buffer, offset)

    override fun writeBlockFully(buffer: ByteBuffer, offset: Long) = cache.writeBlockFully(key, buffer, offset)

    override fun writeBlock(buffer: ByteBufferView, offset: Long): Int = cache.writeBlock(key, buffer, offset)

    override fun writeBlockFully(buffer: ByteBufferView, offset: Long) = cache.writeBlockFully(key, buffer, offset)

    override fun capacity(): Long = key.capacity

    override fun size(): Long = if (Files.exists(key.file)) Files.size(key.file) else 0L

    override fun close() = cache.close(key)
}

/**
 * Resolves a torrent file inside [root].
 *
 * The names follow the rules `bt`'s file storage applies to hostile metainfo paths, reimplemented
 * here because that normaliser is package-private: an element is trimmed, its trailing dots and
 * spaces dropped (which is also what disarms `.` and `..`), and anything left empty - including the
 * name a leading or doubled separator implies - becomes a placeholder. Reproducing those rules
 * rather than inventing stricter ones keeps a download already written to disk addressable.
 *
 * What differs from the library is the root: the elements are joined *under the destination*, with no
 * folder for the torrent's own name. The result is then checked to lie inside it - a torrent is
 * untrusted input, so the guarantee is asserted rather than implied.
 */
internal fun torrentContentPath(root: Path, pathElements: List<String>): Path {
    val relative = normalizeTorrentPath(pathElements, root.fileSystem.separator)
    val normalized = root.resolve(relative).normalize().toAbsolutePath().normalize()
    val confined = root.toAbsolutePath().normalize()
    require(normalized.startsWith(confined) && normalized != confined) {
        "Torrent path ${pathElements.joinToString("/")} escapes its folder"
    }
    return normalized
}

/** Joins the metainfo's path elements into one safe relative name. */
private fun normalizeTorrentPath(pathElements: List<String>, separator: String): String {
    if (pathElements.isEmpty()) return "_"
    val joined = pathElements.joinToString(separator).trim()
    if (joined.isEmpty()) return "_"
    return joined.split(separator).joinToString(separator, transform = ::normalizeName)
}

private fun normalizeName(element: String): String {
    val trimmed = element.trim()
    var end = trimmed.length
    while (end > 0 && (trimmed[end - 1] == '.' || trimmed[end - 1] == ' ')) end--
    return if (end == 0) "_" else trimmed.substring(0, end)
}
