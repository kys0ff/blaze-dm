package org.blaze.engine.api

import kotlinx.serialization.Serializable

@Serializable
data class DownloadMetadata(
    val name: String,
    val totalSize: Long?,
    val files: List<DownloadFileMetadata>? = null
)

@Serializable
data class DownloadFileMetadata(
    val path: String,
    val size: Long
)
