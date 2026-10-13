package org.blaze.engine.api

/**
 * Transport-facing summaries of "inspect before you download" for the two non-plain-HTTP kinds Blaze
 * supports. They exist so the service and UI layers never have to touch the engine's internal HLS/ZIP
 * parse objects: each is a small, display-only projection carrying exactly what a confirm-add dialog
 * needs — quality options, a protection verdict, an entry tree and honest support flags.
 */

// ------------------------------------------------------------------ HLS

/** Blaze's read of how an HLS stream is protected; mirrors the engine classifier without leaking it. */
enum class HlsProtectionLevel { None, OrdinaryEncryption, DrmSuspected, Uncertain }

/** The verdict an HLS inspection reached, driving what the Add Download dialog shows and can start. */
enum class HlsInspectStatus {
    /** A finite, unsupported-by-nothing stream that can be enqueued as-is. */
    Playable,

    /** The stream looks DRM/uncertain; the user must acknowledge the warning before anything starts. */
    NeedsDrmConsent,

    /** A recognised live/event playlist, which Blaze does not assemble into a file. */
    LiveUnsupported,

    /** The URL did not resolve to a playlist Blaze could understand. */
    NotAStream,

    /** Inspection failed for a transport reason (network, auth, oversized playlist). */
    Error,
}

data class HlsVariantInfo(
    val index: Int,
    val bandwidth: Long?,
    val resolution: String?,
    val codecs: String?,
    val language: String?,
    val name: String?,
)

data class HlsInspection(
    val status: HlsInspectStatus,
    val protection: HlsProtectionLevel,
    val variants: List<HlsVariantInfo>,
    val selectedVariantIndex: Int,
    /** Rough total size in bytes when both bitrate and duration are known; null otherwise. */
    val estimatedTotalBytes: Long?,
    /** A short, human-safe explanation for non-playable outcomes; null when [status] is Playable. */
    val message: String?,
)

// ------------------------------------------------------------------ Archive

data class ArchiveEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val compressedSize: Long,
    val uncompressedSize: Long,
    /** False for encrypted entries or unsupported compression methods, which cannot be extracted. */
    val extractable: Boolean,
)

sealed interface ArchiveInspection {
    /** The archive was parsed and its tree is browsable; [selectiveSupported] says whether ranges work. */
    data class Browsable(
        val entries: List<ArchiveEntryInfo>,
        val totalUncompressedBytes: Long,
        val selectiveSupported: Boolean,
        val reasonIfUnsupported: String?,
    ) : ArchiveInspection

    /** Readable as an archive, but the host will not serve byte ranges, so only a full download is possible. */
    data class FullDownloadOnly(val reason: String) : ArchiveInspection

    /** The resource is not a usable ZIP archive (or could not be read at all). */
    data class Unavailable(val reason: String) : ArchiveInspection
}
