package org.blaze.download.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The transport-agnostic description of what to download.
 *
 * Uses kotlinx polymorphism so an HTTP client can send `{"url": "..."}`,
 * `{"magnet": "magnet:?..."}` or `{"torrentFile": "/abs/path"}` interchangeably with the in-process
 * Kotlin builder. No engine request type is exposed here.
 */
@Serializable
sealed interface DownloadSource {
    /** An absolute `http://` or `https://` URL. */
    @Serializable
    @SerialName("url")
    data class Url(val url: String) : DownloadSource

    /** A `magnet:?xt=urn:btih:...` URI. */
    @Serializable
    @SerialName("magnet")
    data class Magnet(val uri: String) : DownloadSource

    /** A local `.torrent` file already on the downloader host's disk. */
    @Serializable
    @SerialName("torrentFile")
    data class TorrentFile(val path: String) : DownloadSource
}

/**
 * Non-secret browser semantics a client may supply so the transfer matches what the browser sent.
 *
 * These are the request headers a server legitimately needs for the *transfer itself* (a `Referer`
 * some CDNs check, the `User-Agent`, `Accept`). They are distinguished from [NewDownloadRequest.credentials]:
 * nothing here is a long-lived secret, and the engine persists neither across a restart.
 */
@Serializable
data class TransferHeaders(
    val userAgent: String? = null,
    val referer: String? = null,
    val accept: String? = null,
    val contentType: String? = null,
    /** Additional safe request headers (for example a cache validator). Sensitive values belong in
     * [NewDownloadRequest.credentials] instead. */
    val additional: Map<String, String> = emptyMap(),
) {
    companion object { val EMPTY: TransferHeaders = TransferHeaders() }

    /** Flattens the typed fields plus extras into a header map, dropping null/blank values. */
    fun toMap(): Map<String, String> = buildMap {
        userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        accept?.takeIf { it.isNotBlank() }?.let { put("Accept", it) }
        contentType?.takeIf { it.isNotBlank() }?.let { put("Content-Type", it) }
        putAll(additional.filterValues { it.isNotBlank() })
    }
}

/**
 * Everything a client can express when creating a download.
 *
 * [credentials] is the explicit "ephemeral authentication material" channel: a bearer token or cookie
 * the transfer genuinely needs. It is never echoed back in a [DownloadDto], never written to the
 * download database or portable metadata, and never logged - the API keeps it separate from
 * [transferHeaders] precisely so callers and the engine can treat it as short-lived.
 */
@Serializable
data class NewDownloadRequest(
    val source: DownloadSource,
    /** Destination directory or full file path. Untrusted transports confine this to an allow-list. */
    val destination: String,
    val filename: String? = null,
    val autoStart: Boolean = true,
    /** HTTP connections for this transfer; null defers to the global engine setting. */
    val connections: Int? = null,
    /** Optional per-transfer ceiling; capability flags report whether the engine honors it. */
    val bandwidthLimitBytesPerSecond: Long? = null,
    /** Size hint if the client (e.g. a browser) already knows it. */
    val expectedSizeBytes: Long? = null,
    val transferHeaders: TransferHeaders = TransferHeaders.EMPTY,
    /** Transient authentication headers. Sensitive: never persisted, echoed or logged. */
    val credentials: Map<String, String> = emptyMap(),
    /** Optional client-supplied key so a retried POST does not create a duplicate download. */
    val idempotencyKey: String? = null,
    /** Torrent: which files (by index) to select; null means "everything". */
    val selectedFileIndices: List<Int>? = null,
    /** Torrent: the per-file size manifest, so progress can be shown before metadata resolves. */
    val files: List<DownloadFileHint>? = null,
    /** Schedule the start for a future instant (epoch millis); null means "as soon as a slot frees". */
    val scheduledAtEpochMs: Long? = null,
    /**
     * How urgent this download is. Null defers to the engine's configured default priority; an
     * explicit value always wins. Only honored when [ApiCapabilities.prioritySupport] says so.
     */
    val priority: DownloadPriority? = null,
) {
    /** The combined header set handed to the engine for this (in-memory) transfer only. */
    fun effectiveHeaders(): Map<String, String> = transferHeaders.toMap() + credentials

    /** A copy safe to log: credentials are dropped, non-secret headers retained. */
    fun redacted(): String = buildString {
        append("NewDownloadRequest(source=").append(describeSource())
        append(", destination=").append(destination)
        append(", filename=").append(filename)
        append(", autoStart=").append(autoStart)
        append(", credentials=<").append(credentials.size).append(" redacted>")
        append(", idempotencyKey=").append(idempotencyKey ?: "-")
        append(")")
    }

    private fun describeSource(): String = when (source) {
        is DownloadSource.Url -> "Url(${source.url.originOf()})"
        is DownloadSource.Magnet -> "Magnet(${source.uri.take(24)}...)"
        is DownloadSource.TorrentFile -> "TorrentFile(${source.filenameHint()})"
    }
}

private fun String.originOf(): String = runCatching {
    java.net.URI(this).let { "${it.scheme}://${it.host}" }
}.getOrDefault("<invalid-url>")

private fun DownloadSource.TorrentFile.filenameHint(): String = path.substringAfterLast('/')

/**
 * A size/path hint for one file in a multi-file download, supplied by a client that already probed the
 * source. Display-only; it carries no credential material, and the engine still confines every written
 * file beneath the validated destination directory regardless of the paths listed here.
 */
@kotlinx.serialization.Serializable
data class DownloadFileHint(
    val path: String,
    val size: Long = 0,
)
