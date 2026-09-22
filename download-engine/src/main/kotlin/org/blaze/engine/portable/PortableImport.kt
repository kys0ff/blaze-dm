package org.blaze.engine.portable

import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.PortableDownloadInfo
import org.blaze.engine.api.PortableTransferKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * Read-only discovery of a portable incomplete download, plus the small path helpers the import
 * flow needs. Nothing here mutates the filesystem — turning a detection into a live download is the
 * engine's job ([org.blaze.engine.core.DownloadManager.importPortableDownload]) so that all of the
 * normal destination-conflict and persistence rules still apply.
 *
 * Detection is by content, never by file name (§23): an HTTP artifact is recognised by its embedded
 * locator trailer, and a torrent by its magic-prefixed `.blaze-portable` marker found by walking up
 * from the selected path.
 */
object PortableImport {

    /** A safe, display-only summary, or null when [path] is not a Blaze portable artifact. */
    fun detect(path: Path): PortableDownloadInfo? {
        inspectPortableFile(path)?.let { artifact ->
            if (artifact.kind == PortableKind.HTTP) return httpInfo(path, artifact)
        }
        resolveTorrentDestination(path)?.let { dir ->
            TorrentPortableFile.read(TorrentPortableFile.markerPath(dir))?.let { return torrentInfo(it) }
        }
        return null
    }

    private fun httpInfo(path: Path, artifact: PortableArtifact): PortableDownloadInfo {
        val manifest = artifact.manifest as? PortableManifest.Http
        return PortableDownloadInfo(
            kind = PortableTransferKind.HTTP,
            suggestedName = baseName(path),
            source = manifest?.originalUrl,
            totalBytes = artifact.contentLength,
            availableBytes = manifest?.downloadedBytes ?: 0L,
            resumable = manifest != null && !artifact.stale,
            // We cannot tell from portable state alone whether the origin demands auth (secrets are
            // never embedded), so always let the UI offer credentials on resume.
            mayRequireCredentials = true
        )
    }

    private fun torrentInfo(record: TorrentManifestRecord): PortableDownloadInfo {
        val m = record.manifest
        return PortableDownloadInfo(
            kind = PortableTransferKind.TORRENT,
            suggestedName = m.name,
            source = m.trackers.firstOrNull() ?: m.name,
            totalBytes = m.contentLength,
            availableBytes = m.downloadedBytes,
            resumable = m.torrentFile.isNotEmpty(),
            multiFile = m.multiFile,
            files = m.files.map { DownloadFileMetadata(it.relativePath, it.size) },
            mayRequireCredentials = false
        )
    }

    /**
     * Finds the directory that holds a torrent's portable marker for a [path] that may be the folder
     * itself or any file inside the torrent, by walking up a bounded number of levels. Returns null
     * when no marker is found. The bound stops a stray selection from scanning the whole tree.
     */
    fun resolveTorrentDestination(path: Path): Path? {
        val maxDepth = 6
        var current: Path? = if (Files.isDirectory(path)) path else path.toAbsolutePath().parent
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (Files.isRegularFile(TorrentPortableFile.markerPath(current))) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** Strips a conventional `.part` suffix for display; the extension is a hint, never proof. */
    private fun baseName(path: Path): String {
        val name = path.fileName.toString()
        return if (name.endsWith(".part")) name.removeSuffix(".part") else name
    }
}
