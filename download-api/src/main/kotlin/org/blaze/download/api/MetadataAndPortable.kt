package org.blaze.download.api

import kotlinx.serialization.Serializable

/**
 * The result of probing a source before committing to a download (size / file manifest).
 *
 * Exposed to clients so a "add download" dialog - in the GUI, the CLI or a browser bridge - can preview a
 * torrent's files or an HTTP resource's size without the client touching the engine's metadata machinery.
 */
@Serializable
data class DownloadMetadataDto(
    val name: String,
    val totalBytes: Long? = null,
    val files: List<DownloadFile>? = null,
    /**
     * Whether probing proved that the source can continue a paused download. Null when the probe
     * could not tell, which a client must render differently from an explicit `false`.
     */
    val resumeSupported: Boolean? = null,
)

/**
 * The outcome of fetching a web-served `.torrent` file so it can be previewed and then added as an
 * ordinary local torrent.
 *
 * [torrentFile] is the absolute path of the metainfo file the engine saved to its own cache, so a
 * client hands that path back to [DownloadsApi.create] to start the transfer from a local `.torrent`
 * rather than re-fetching the URL. It is display/selection metadata only - never any credential
 * material. Returns null (rather than an empty preview) when the URL served nothing parsable.
 */
@Serializable
data class TorrentPreviewDto(
    val torrentFile: String,
    val name: String,
    val totalBytes: Long? = null,
    val files: List<DownloadFile>? = null,
)

/**
 * A safe, display-only summary of a portable (crash-/transfer-) download found on disk.
 *
 * It is deliberately free of secrets: no headers, cookies or tokens are ever carried here. [source] is a
 * human-meaningful label only (the HTTP URL or the torrent name), and [mayRequireCredentials] tells a
 * client that resuming might need fresh authentication it must supply.
 */
@Serializable
data class PortableInfoDto(
    val kind: PortableKindDto,
    val suggestedName: String,
    val source: String? = null,
    val totalBytes: Long,
    val availableBytes: Long,
    val resumable: Boolean,
    val multiFile: Boolean = false,
    val files: List<DownloadFile>? = null,
    val mayRequireCredentials: Boolean = false,
)

@Serializable
enum class PortableKindDto { HTTP, TORRENT }
