package org.blaze.presentation.screens.downloads

import org.blaze.domain.models.Download

/**
 * Which desktop integrations the host can actually perform. Drives whether the download row's
 * context menu shows (or grays out) Open / Show in folder / Open source link, so the UI never
 * offers an action the current platform can't carry out.
 */
data class DownloadCapabilities(
    val canOpenFiles: Boolean = false,
    val canRevealInFolder: Boolean = false,
    val canBrowseLinks: Boolean = false,
    val canCopy: Boolean = false
)

data class DownloadsState(
    val downloads: List<Download> = emptyList(),
    val filteredDownloads: List<Download> = emptyList(),
    val searchQuery: String = "",
    val maxConcurrentDownloads: Int = 0,
    val capabilities: DownloadCapabilities = DownloadCapabilities()
)
