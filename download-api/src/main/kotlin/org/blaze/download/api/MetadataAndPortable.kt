package org.blaze.download.api

import kotlinx.serialization.SerialName
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

// ----------------------------------------------------------- HLS inspection

/**
 * Blaze's read of how an HLS stream is protected. Mirrors the engine classifier without exposing it.
 * [DrmSuspected] and [Uncertain] both require a user acknowledgement before any download is attempted;
 * neither means Blaze will attempt to defeat protection — acknowledgement only permits a normal, supported
 * download attempt.
 */
@Serializable
enum class HlsProtectionDto { None, OrdinaryEncryption, DrmSuspected, Uncertain }

/** The verdict an HLS inspection reached; drives what the Add Download dialog shows and may start. */
@Serializable
enum class HlsInspectStatusDto { Playable, NeedsDrmConsent, LiveUnsupported, NotAStream, Error }

/** One selectable rendition advertised by a master playlist. */
@Serializable
data class HlsVariantDto(
    val index: Int,
    val bandwidth: Long? = null,
    val resolution: String? = null,
    val codecs: String? = null,
    val language: String? = null,
    val name: String? = null,
)

/** The result of inspecting an `.m3u8` before committing to a download; carries no secrets. */
@Serializable
data class HlsInspectionDto(
    val status: HlsInspectStatusDto,
    val protection: HlsProtectionDto,
    val variants: List<HlsVariantDto> = emptyList(),
    val selectedVariantIndex: Int = -1,
    val estimatedTotalBytes: Long? = null,
    val message: String? = null,
)

// ----------------------------------------------------------- Archive inspection

/** One member of a remote ZIP archive, read from its central directory. */
@Serializable
data class ArchiveEntryDto(
    val name: String,
    val isDirectory: Boolean,
    val compressedSize: Long,
    val uncompressedSize: Long,
    /** False for encrypted members or unsupported compression methods, which cannot be extracted. */
    val extractable: Boolean,
)

/**
 * The outcome of inspecting a remote ZIP archive.
 *
 * [Browsable] means the tree was parsed and [Browsable.selectiveSupported] says whether byte ranges make
 * partial retrieval possible. [FullDownloadOnly] means the archive is readable but the host will not serve
 * ranges, so only a whole-archive download is offered. [Unavailable] means the resource is not a usable ZIP.
 */
@Serializable
sealed interface ArchiveInspectionDto {
    @Serializable
    @SerialName("browsable")
    data class Browsable(
        val entries: List<ArchiveEntryDto> = emptyList(),
        val totalUncompressedBytes: Long = 0,
        val selectiveSupported: Boolean,
        val reasonIfUnsupported: String? = null,
    ) : ArchiveInspectionDto

    @Serializable
    @SerialName("fullDownloadOnly")
    data class FullDownloadOnly(val reason: String) : ArchiveInspectionDto

    @Serializable
    @SerialName("unavailable")
    data class Unavailable(val reason: String) : ArchiveInspectionDto
}
