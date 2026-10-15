package org.blaze.engine.persistence

import kotlinx.serialization.Serializable
import org.blaze.engine.api.DownloadFileMetadata

@Serializable
data class DownloadRecord(
    val id: String,
    val name: String,
    val type: String, // "HTTP", "TORRENT", "HLS" or "ARCHIVE"
    val url: String? = null,
    val torrentPath: String? = null,
    val magnetUri: String? = null,
    /** http(s) URL serving a .torrent metainfo; only present for torrent-URL records. */
    val torrentUrl: String? = null,
    val destination: String,
    val state: String,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val addedAt: Long,
    val scheduledAt: Long? = null,
    val retryCount: Int = 0,
    val fileIndices: List<Int>? = null,
    val files: List<DownloadFileMetadata>? = null,
    /** Scheduling priority name; unknown/absent values are read back as NORMAL. */
    val priority: String = "NORMAL",
    /** Proven ranged-read support of the source; null means the transfer never answered the question. */
    val resumeSupported: Boolean? = null,
    /** HLS: master-playlist variant to select; -1 means highest bandwidth. Only set for HLS records. */
    val variantIndex: Int? = null,
    /** HLS: whether the user already accepted a potential-DRM warning. Never unlocks circumvention. */
    val drmAcknowledged: Boolean = false,
    /**
     * ARCHIVE: entry names chosen for selective retrieval; null means the whole archive. Request headers
     * are deliberately never persisted, so a resumed archive re-prompts for any credentials it needs.
     */
    val selectedEntries: List<String>? = null
)
