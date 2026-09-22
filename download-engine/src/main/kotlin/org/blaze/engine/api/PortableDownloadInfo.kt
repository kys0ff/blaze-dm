package org.blaze.engine.api

/**
 * A safe, display-only summary of a portable incomplete download discovered on disk.
 *
 * It is deliberately free of anything sensitive: no headers, cookies or tokens are ever carried here
 * (§6, §16). [source] is a human-meaningful identifier only — the HTTP URL or the torrent name — and
 * [files]/[multiFile] describe a torrent's layout for the import preview.
 */
data class PortableDownloadInfo(
    val kind: PortableTransferKind,
    /** Best name to resume under: the artifact's own file name (HTTP) or the torrent name. */
    val suggestedName: String,
    /** Safe source label for display: the URL (HTTP) or torrent name; never a secret. */
    val source: String?,
    val totalBytes: Long,
    /** Bytes already present and believed usable (chunk bitmap / verified-piece hint). */
    val availableBytes: Long,
    /** False when the manifest is stale/absent, so the file is only a raw prefix to restart from. */
    val resumable: Boolean,
    val multiFile: Boolean = false,
    val files: List<DownloadFileMetadata>? = null,
    /**
     * True when resuming may need credentials this machine must supply (an HTTP resource that is
     * authenticated). Portable state never embeds secrets, so this is surfaced so the UI can ask.
     */
    val mayRequireCredentials: Boolean = false
)

/** Which protocol a detected portable artifact carries. */
enum class PortableTransferKind { HTTP, TORRENT }
