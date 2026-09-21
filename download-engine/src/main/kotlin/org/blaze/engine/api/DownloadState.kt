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

    val isActive: Boolean
        get() = when (this) {
            Downloading, Starting, Resuming, Verifying, ResolvingMetadata, Seeding, Pausing -> true
            else -> false
        }

    companion object {
        fun fromString(raw: String): DownloadState = when (raw.uppercase()) {
            "COMPLETED" -> Completed
            "PAUSED" -> Paused
            "FAILED" -> Failed
            "CANCELLED" -> Cancelled
            "SEEDING" -> Seeding
            "DOWNLOADING" -> Downloading
            "STARTING" -> Starting
            "RESUMING" -> Resuming
            "VERIFYING" -> Verifying
            "RESOLVINGMETADATA" -> ResolvingMetadata
            else -> Queued
        }

        fun toString(state: DownloadState): String =
            state::class.simpleName?.uppercase() ?: "QUEUED"
    }
}
