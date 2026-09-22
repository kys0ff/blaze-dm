package org.blaze.service.mapping

import org.blaze.download.api.ApiErrorCode
import org.blaze.download.api.DownloadSource
import org.blaze.download.api.NewDownloadRequest
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.TorrentSource
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
        val source = Path.of(path)
        val baseName = request.filename?.takeIf { it.isNotBlank() }
            ?: source.fileName?.toString()?.removeSuffix(".torrent")
            ?: "download"
        return DownloadRequest.Torrent(baseName, TorrentSource.File(source), dir.resolve(baseName), request.selectedFileIndices)
    }

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
