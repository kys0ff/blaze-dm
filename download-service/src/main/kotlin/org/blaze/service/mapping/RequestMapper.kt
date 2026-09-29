package org.blaze.service.mapping

import org.blaze.download.api.ApiErrorCode
import org.blaze.download.api.DownloadSource
import org.blaze.download.api.NewDownloadRequest
import org.blaze.engine.api.CopyName
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.settings.FileConflictBehavior
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Turns a transport-agnostic [NewDownloadRequest] into the engine's [DownloadRequest].
 *
 * This is where source validation and destination layout live, so no transport has to reimplement them
 * and no engine caller has to know browser URL conventions. The request's combined (non-persisted)
 * headers are forwarded to the engine verbatim for the in-memory transfer, but the resolved name/URL are
 * the only thing that reaches the persisted record.
 */
object RequestMapper {

    /**
     * @param destinationDir the already-validated absolute directory to write into.
     */
    fun toEngineRequest(request: NewDownloadRequest, destinationDir: Path): DownloadRequest =
        when (val source = request.source) {
            is DownloadSource.Url -> http(source.url, request, destinationDir)
            is DownloadSource.Magnet -> torrentMagnet(source.uri, request, destinationDir)
            is DownloadSource.TorrentFile -> torrentFile(source.path, request, destinationDir)
        }

    /**
     * Gives a brand-new download a destination of its own, so two never share one.
     *
     * A torrent's destination *is* its folder, and the name that folder is built from - the metainfo's,
     * the magnet's `dn`, the URL's last segment - repeats itself as soon as the same torrent is fetched
     * twice. The step aside has to happen here, while the download is being created, and not later where
     * HTTP settles its collisions: a torrent creates its folder the moment it starts, so by then "the
     * folder exists" no longer tells a collision apart from resume, and renaming on that test would
     * move a download out of the half-finished data it is resuming from.
     *
     * [FileConflictBehavior.ASK] and [FileConflictBehavior.RENAME] both mean "keep what is already
     * there and make room" - ASK because whoever is asking has already been offered the choice of
     * removing the existing copy, and did not take it. The other two are left exactly where the caller
     * pointed them: overwriting a folder a torrent already filled is a decision about the user's files
     * that this layer must not make silently.
     *
     * @param taken the question "does something already live at this path".
     */
    fun claimDestination(
        request: DownloadRequest,
        behavior: FileConflictBehavior,
        taken: (Path) -> Boolean,
    ): DownloadRequest {
        if (request !is DownloadRequest.Torrent) return request
        if (behavior != FileConflictBehavior.ASK && behavior != FileConflictBehavior.RENAME) return request
        val parent = request.destination.parent ?: return request
        if (!taken(request.destination)) return request

        val folder = request.destination.fileName.toString()
        var attempt = 1
        var claimed = parent.resolve(CopyName.of(folder, attempt))
        while (taken(claimed)) {
            attempt++
            claimed = parent.resolve(CopyName.of(folder, attempt))
        }
        // The name travels with the folder: the row in the list and the directory in the file manager
        // have to read the same, or the copy looks like it was never made at all.
        return request.copy(name = claimed.fileName.toString(), destination = claimed)
    }

    private fun http(url: String, request: NewDownloadRequest, dir: Path): DownloadRequest.Http {
        val trimmed = url.trim()
        val scheme = runCatching { URI(trimmed).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            throw org.blaze.download.api.DownloadApiException(
                ApiErrorCode.INVALID_URL,
                "only http and https URLs are supported",
            )
        }
        val name = request.filename?.takeIf { it.isNotBlank() } ?: deriveHttpName(trimmed)
        return DownloadRequest.Http(
            name = name,
            url = trimmed,
            destination = dir.resolve(name),
            headers = request.effectiveHeaders(),
            segmentCount = request.connections?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun torrentMagnet(uri: String, request: NewDownloadRequest, dir: Path): DownloadRequest.Torrent {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("magnet:", ignoreCase = true)) {
            throw org.blaze.download.api.DownloadApiException(ApiErrorCode.UNSUPPORTED_SOURCE, "not a magnet URI")
        }
        val name = request.filename?.takeIf { it.isNotBlank() } ?: deriveMagnetName(trimmed)
        return DownloadRequest.Torrent(name, TorrentSource.Magnet(trimmed), dir.resolve(name), request.selectedFileIndices)
    }

    private fun torrentFile(path: String, request: NewDownloadRequest, dir: Path): DownloadRequest.Torrent {
        if (path.isBlank()) {
            throw org.blaze.download.api.DownloadApiException(ApiErrorCode.INVALID_REQUEST, "torrent path is blank")
        }
        // A web URL that serves a .torrent is a *torrent* download whose metainfo arrives over
        // HTTP, not a plain file to save: routing it to the file branch would hand bt a garbage
        // path and the user would watch a "download" that can never start.
        if (isHttpUrl(path)) return torrentFromUrl(path, request, dir)
        val source = runCatching { Path.of(path) }.getOrElse {
            throw org.blaze.download.api.DownloadApiException(ApiErrorCode.INVALID_REQUEST, "not a valid torrent path: $path")
        }
        val baseName = request.filename?.takeIf { it.isNotBlank() }
            ?: (source.fileName?.toString() ?: "").removeTorrentSuffix()
                .ifBlank { "download" }
        return DownloadRequest.Torrent(baseName, TorrentSource.File(source), dir.resolve(baseName), request.selectedFileIndices)
    }

    private fun torrentFromUrl(url: String, request: NewDownloadRequest, dir: Path): DownloadRequest.Torrent {
        val trimmed = url.trim()
        val baseName = request.filename?.takeIf { it.isNotBlank() }
            ?: deriveTorrentUrlName(trimmed)
        return DownloadRequest.Torrent(baseName, TorrentSource.Url(trimmed), dir.resolve(baseName), request.selectedFileIndices)
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    /** Last path segment of a torrent URL, minus the `.torrent` suffix: "…/Big.Buck.torrent" → "Big.Buck". */
    private fun deriveTorrentUrlName(url: String): String {
        val segment = url.substringBefore('?').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(segment, StandardCharsets.UTF_8).ifBlank { segment } }
            .getOrDefault(segment)
        return decoded.removeTorrentSuffix()
            .takeIf { it.isNotBlank() }
            ?: "torrent-download"
    }

    /** `.torrent` is a source marker, not part of the display name; the ending is stripped case-insensitively. */
    private fun String.removeTorrentSuffix(): String =
        if (endsWith(".torrent", ignoreCase = true)) dropLast(".torrent".length) else this

    private fun deriveHttpName(url: String): String {
        val decoded = runCatching { URLDecoder.decode(url, StandardCharsets.UTF_8).ifBlank { url } }
            .getOrDefault(url)
        return decoded
            .substringAfterLast('/')
            .substringBefore('?')
            .replace('+', ' ')
            .takeIf { it.isNotBlank() }
            ?: "download"
    }

    private fun deriveMagnetName(uri: String): String {
        val dn = uri.split('&', '?')
            .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrDefault(it) }
        return dn?.takeIf { it.isNotBlank() } ?: "torrent-download"
    }
}
