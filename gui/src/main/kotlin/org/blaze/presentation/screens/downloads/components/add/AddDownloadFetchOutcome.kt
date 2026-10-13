package org.blaze.presentation.screens.downloads.components.add

import org.blaze.domain.repository.DownloadMetadata
import java.net.URI

/** A source that names a local `.torrent` file: a path, not a web link (any `://` makes it a URL). */
internal fun isLocalTorrentPath(source: String): Boolean {
    val s = source.trim()
    return s.endsWith(".torrent", ignoreCase = true) &&
        !s.contains("://") &&
        !s.startsWith("magnet:", ignoreCase = true)
}

/** The torrent family: magnets plus local `.torrent` files. */
internal fun isTorrentFamilySource(source: String): Boolean =
    source.trim().startsWith("magnet:", ignoreCase = true) || isLocalTorrentPath(source)

/** Endings that name a served *page*, never a file worth saving blindly. */
private val WEB_PAGE_EXTENSIONS = setOf(
    "html", "htm", "xhtml", "shtml", "asp", "aspx", "php", "jsp", "jspx", "cfm", "do", "action",
)

/** True when an http(s) source is worth asking the page-link question about. */
internal fun isHttpSource(source: String): Boolean =
    source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)

/**
 * True when an http(s) link serves a `.torrent` metainfo file - a torrent whose descriptor arrives over
 * the web rather than a swarm-resolved magnet or a local file. The query never names the file, so
 * "…/My.torrent?token=1" counts. This is the shape the opt-in torrent-preview flow keys off.
 */
internal fun isHttpTorrentFileUrl(source: String): Boolean {
    val trimmed = source.trim()
    return isHttpSource(trimmed) && trimmed.substringBefore('?').endsWith(".torrent", ignoreCase = true)
}

/**
 * True when an http(s) link reads as an HLS playlist by its path ending. This is only a
 * *pre-inspection hint* — the dialog still calls `inspectHls` and routes by the parsed verdict
 * (master vs media, protection, VOD vs live), never by this ending alone, exactly as the `.torrent`
 * hint above is confirmed by a real probe before it becomes a torrent download.
 */
internal fun isHlsPlaylistUrl(source: String): Boolean {
    val trimmed = source.trim()
    return isHttpSource(trimmed) && trimmed.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
}

/**
 * True when an http(s) link reads as a remote ZIP archive by its path ending. Like [isHlsPlaylistUrl]
 * it only decides whether to *attempt* an archive inspection; [org.blaze.domain.repository.DownloadRepository.inspectArchive]
 * then determines from the central directory whether the resource really is a browsable archive.
 */
internal fun isRemoteArchiveUrl(source: String): Boolean {
    val trimmed = source.trim()
    return isHttpSource(trimmed) && trimmed.substringBefore('?').endsWith(".zip", ignoreCase = true)
}

/**
 * True when an http(s) link reads as a **page** rather than a file: its path is empty (a site root),
 * its last segment carries no extension (a route such as `/share/abc123` or `/download?id=5`), or it
 * ends in a page/script extension.
 *
 * Shape only — no network, no probe — so it is safe to evaluate on every keystroke. It deliberately
 * errs towards "page": a false positive costs one confirmation click, a false negative costs a
 * silently saved lump of HTML. A URL that cannot even be parsed is left alone, since the probe and
 * the unsupported-source hint already cover garbage input.
 */
internal fun looksLikeWebPageLink(source: String): Boolean {
    val path = runCatching { URI(source.trim()).rawPath }.getOrNull() ?: return false
    val segment = path.substringAfterLast('/').trim()
    if (segment.isEmpty()) return true
    val extension = segment.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension.isEmpty() || extension in WEB_PAGE_EXTENSIONS
}

/**
 * What a metadata probe answered, and what the dialog may conclude from it. The decision table
 * lives here (rather than inline in the composable) because the three answers lead to three
 * different UX contracts:
 *
 * - [Resolved]: the normal review step with real name/size/file selection.
 * - [Unresolved]: a torrent whose metainfo the swarm (or the metainfo server) could not supply in
 *   time. This is *not* a failure - a cold magnet legitimately takes minutes - so the download
 *   stays queueable and the review step degrades to "details pending" instead of going blank.
 * - [Rejected]: a link whose probe failed outright. Queuing it would only add a download that is
 *   born failing, so the dialog stays on the input step with the reason visible.
 */
internal sealed interface AddDownloadFetchOutcome {
    data class Resolved(val metadata: DownloadMetadata) : AddDownloadFetchOutcome

    data class Unresolved(val source: String) : AddDownloadFetchOutcome

    data class Rejected(val reason: String) : AddDownloadFetchOutcome

    companion object {
        /**
         * [probeError] is the exception the probe threw; a *null* result with no error is the
         "answered, but nothing yet" case a magnet legitimately produces while the swarm is cold.
         */
        fun of(source: String, metadata: DownloadMetadata?, probeError: String?): AddDownloadFetchOutcome = when {
            metadata != null -> Resolved(metadata)
            probeError == null && isTorrentFamilySource(source) -> Unresolved(source)
            else -> Rejected(probeError ?: source)
        }
    }
}
