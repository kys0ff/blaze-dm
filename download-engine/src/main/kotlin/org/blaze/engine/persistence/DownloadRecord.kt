package org.blaze.engine.persistence

import kotlinx.serialization.Serializable
import org.blaze.engine.api.DownloadFileMetadata

@Serializable
data class DownloadRecord(
    val id: String,
    val name: String,
    val type: String, // "HTTP" or "TORRENT"
    val url: String? = null,
    val torrentPath: String? = null,
    val magnetUri: String? = null,
    val destination: String,
    val state: String,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val addedAt: Long,
    val scheduledAt: Long? = null,
    val retryCount: Int = 0,
    val fileIndices: List<Int>? = null,
    val files: List<DownloadFileMetadata>? = null
)
