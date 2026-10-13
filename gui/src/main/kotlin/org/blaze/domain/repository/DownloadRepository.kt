package org.blaze.domain.repository

import kotlinx.coroutines.flow.Flow
import org.blaze.domain.models.Download
import org.blaze.download.api.DownloadPriority

interface DownloadRepository {
    val downloads: Flow<List<Download>>

    /**
     * Probes a source for its metadata. [credentials] are ephemeral auth headers (a bearer token or
     * Cookie) sent only for this probe so a sign-in-protected HTTP link can be inspected; they are
     * never stored.
     */
    suspend fun fetchMetadata(url: String, credentials: Map<String, String> = emptyMap()): DownloadMetadata?

    /**
     * Downloads the `.torrent` served at [url] (an http/https link), parses its file list and stashes the
     * metainfo on disk so the caller can preview it and then queue it as a normal local torrent.
     * [credentials] are ephemeral auth headers sent only for this fetch. Returns null when the URL serves
     * nothing parsable as a torrent. The returned [TorrentPreview.torrentFile] is the local path to add.
     */
    suspend fun prepareTorrentFromUrl(
        url: String,
        credentials: Map<String, String> = emptyMap(),
    ): TorrentPreview?

    suspend fun addDownload(
        url: String,
        savePath: String,
        name: String? = null,
        fileIndices: List<Int>? = null,
        totalSize: Long? = null,
        files: List<DownloadFile>? = null,
        scheduledAt: Long? = null,
        priority: DownloadPriority? = null,
        /** Ephemeral auth headers for this transfer only; forwarded to the engine, never persisted. */
        credentials: Map<String, String> = emptyMap(),
    )

    /**
     * Inspects an HLS (`.m3u8`) playlist before adding it: lists variants and classifies protection without
     * downloading any media. The Add Download dialog uses the verdict to raise a potential-DRM warning. [credentials]
     * are ephemeral and never persisted. Acknowledging a warning never unlocks DRM circumvention.
     */
    suspend fun inspectHls(
        url: String,
        variantIndex: Int = -1,
        credentials: Map<String, String> = emptyMap(),
    ): org.blaze.download.api.HlsInspectionDto

    /**
     * Inspects a remote ZIP archive via byte ranges, returning its entry tree and whether selective
     * retrieval is possible - without downloading the whole archive. [credentials] are ephemeral.
     */
    suspend fun inspectArchive(
        url: String,
        credentials: Map<String, String> = emptyMap(),
    ): org.blaze.download.api.ArchiveInspectionDto

    /**
     * Queues a download described by an explicit [source] (an HLS playlist or a remote archive with a
     * chosen entry selection), rather than inferring the handler from a bare URL. The typed source carries
     * the variant/selection/DRM-choice the user made in the dialog; [credentials] reach only this transfer.
     */
    suspend fun addDownloadWithSource(
        source: org.blaze.download.api.DownloadSource,
        savePath: String,
        name: String? = null,
        totalSize: Long? = null,
        scheduledAt: Long? = null,
        priority: DownloadPriority? = null,
        credentials: Map<String, String> = emptyMap(),
    )

    suspend fun getDestinationPath(url: String, savePath: String, name: String? = null): String
    suspend fun pauseDownload(id: String)
    suspend fun resumeDownload(id: String)
    suspend fun cancelDownload(id: String)
    suspend fun removeDownload(id: String, deleteFile: Boolean = false)
    suspend fun retryDownload(id: String)
    suspend fun setPriority(id: String, priority: DownloadPriority)
    suspend fun pauseAll()
    suspend fun resumeAll()
    suspend fun clearCompleted()

    /**
     * Reads a moved artifact at [artifactPath] and returns a display-only summary when it is a Blaze
     * portable download, or null otherwise. Detection is by embedded magic, never by file name.
     */
    suspend fun detectPortable(artifactPath: String): PortableInfo?

    /**
     * Imports a detected portable artifact into [destinationDir], reconstructing local state so the
     * download resumes. Returns true when a resumable download was queued for the artifact.
     */
    suspend fun importPortable(artifactPath: String, destinationDir: String): Boolean
}

/** Protocol family a detected portable artifact carries. */
enum class PortableKind { HTTP, TORRENT }

/**
 * Domain view of a discovered portable download. Deliberately free of engine types and of any
 * credential material — [source] is a display label only (URL or torrent name).
 */
data class PortableInfo(
    val kind: PortableKind,
    val suggestedName: String,
    val source: String?,
    val totalBytes: Long,
    val availableBytes: Long,
    val resumable: Boolean,
    val multiFile: Boolean,
    val mayRequireCredentials: Boolean
)

data class DownloadMetadata(
    val name: String,
    val totalSize: Long?,
    val files: List<DownloadFile>? = null,
    /** Proven resume capability of the source, or null when the probe could not tell. */
    val resumeSupported: Boolean? = null
)

/**
 * A downloaded-and-parsed `.torrent` metainfo file, ready to preview and then queue as a local torrent.
 * [torrentFile] is the absolute path of the metainfo on disk - handing it back to [addDownload] starts the
 * transfer from the local file instead of re-fetching [url].
 */
data class TorrentPreview(
    val torrentFile: String,
    val name: String,
    val totalSize: Long?,
    val files: List<DownloadFile>?,
)

data class DownloadFile(
    val name: String,
    val size: Long,
    val index: Int
)
