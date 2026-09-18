package org.blaze.engine.api

sealed interface DownloadState {
    data object Queued : DownloadState
    data object Starting : DownloadState
    data object Downloading : DownloadState
    data object Pausing : DownloadState
    data object Paused : DownloadState
    data object Resuming : DownloadState
    data object Verifying : DownloadState
    data object Completed : DownloadState
    data object Failed : DownloadState
    data object Cancelled : DownloadState
    
    // Torrent specific
    data object ResolvingMetadata : DownloadState
    data object Seeding : DownloadState
}
