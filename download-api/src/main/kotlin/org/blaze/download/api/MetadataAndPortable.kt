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
