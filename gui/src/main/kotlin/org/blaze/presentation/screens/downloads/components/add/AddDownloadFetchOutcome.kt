package org.blaze.presentation.screens.downloads.components.add

import org.blaze.domain.repository.DownloadMetadata

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
