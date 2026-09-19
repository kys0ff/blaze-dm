package org.blaze.presentation.screens.downloads

import org.blaze.domain.models.Download

data class DownloadsState(
    val downloads: List<Download> = emptyList(),
    val filteredDownloads: List<Download> = emptyList(),
    val searchQuery: String = "",
    val maxConcurrentDownloads: Int = 0
)
