package org.blaze.engine.api

import kotlinx.serialization.Serializable

@Serializable
data class DownloadMetadata(
    val name: String,
    val totalSize: Long?,
    val files: List<DownloadFileMetadata>? = null,
    /**
     * Whether probing proved that a later pause/resume can continue where it stopped. Null when the
     * probe could not tell - only a proven answer is worth warning a user about.
     */
    val resumeSupported: Boolean? = null
)

@Serializable
data class DownloadFileMetadata(
    val path: String,
    val size: Long
)
