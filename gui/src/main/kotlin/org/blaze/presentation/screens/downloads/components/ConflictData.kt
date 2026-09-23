package org.blaze.presentation.screens.downloads.components

import org.blaze.download.api.DownloadPriority

data class ConflictData(
    val url: String,
    val savePath: String,
    val name: String?,
    val fileName: String,
    val fullPathStr: String,
    val fileIndices: List<Int>? = null,
    val conflictingDownloadId: String? = null,
    val priority: DownloadPriority? = null,
)
