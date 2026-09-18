package org.blaze.engine.api

sealed interface DownloadError {
    data object NetworkUnavailable : DownloadError
    data object Timeout : DownloadError
    data object Unauthorized : DownloadError
    data object NotFound : DownloadError
    data object DiskFull : DownloadError
    data object RangeUnsupported : DownloadError
    data object InvalidTorrent : DownloadError
    data object MetadataTimeout : DownloadError
    data object Cancelled : DownloadError

    data class NetworkFailure(val message: String) : DownloadError
    data class FileSystemError(val message: String) : DownloadError
    data class Unknown(val message: String) : DownloadError
}
