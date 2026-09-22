package org.blaze.service.mapping

import org.blaze.download.api.ApiErrorBody
import org.blaze.download.api.ApiErrorCode
import org.blaze.engine.api.DownloadError

/**
 * Maps engine transfer failures onto stable public [ApiErrorCode]s.
 *
 * Internal exception names never reach a client; only the code plus a short human message do. Codes
 * here describe the *outcome* a client acts on (retry? fix the URL? free disk?), not the class that
 * threw.
 */
fun DownloadError.toApiError(): ApiErrorBody = when (this) {
    is DownloadError.NetworkUnavailable ->
        ApiErrorBody(ApiErrorCode.NETWORK_ERROR, "The network is unavailable")
    is DownloadError.Timeout ->
        ApiErrorBody(ApiErrorCode.NETWORK_ERROR, "The transfer timed out")
    is DownloadError.Unauthorized ->
        ApiErrorBody(ApiErrorCode.DOWNLOAD_FAILED, "The source rejected the request as unauthorized")
    is DownloadError.NotFound ->
        ApiErrorBody(ApiErrorCode.DOWNLOAD_FAILED, "The source was not found")
    is DownloadError.DiskFull ->
        ApiErrorBody(ApiErrorCode.DESTINATION_UNAVAILABLE, "No space left on the destination device")
    is DownloadError.RangeUnsupported ->
        ApiErrorBody(ApiErrorCode.DOWNLOAD_FAILED, "The source does not support ranged requests")
    is DownloadError.InvalidTorrent ->
        ApiErrorBody(ApiErrorCode.INVALID_TORRENT, "The torrent source is invalid")
    is DownloadError.MetadataTimeout ->
        ApiErrorBody(ApiErrorCode.NETWORK_ERROR, "Torrent metadata could not be resolved in time")
    is DownloadError.Cancelled ->
        ApiErrorBody(ApiErrorCode.DOWNLOAD_FAILED, "The download was cancelled")
    is DownloadError.NetworkFailure ->
        ApiErrorBody(ApiErrorCode.NETWORK_ERROR, message)
    is DownloadError.FileSystemError ->
        ApiErrorBody(ApiErrorCode.DESTINATION_UNAVAILABLE, message)
    is DownloadError.Unknown ->
        ApiErrorBody(ApiErrorCode.DOWNLOAD_FAILED, message)
}

/**
 * Maps a thrown exception (engine or otherwise) to an [ApiErrorBody] for the transport envelope.
 *
 * A [org.blaze.download.api.DownloadApiException] already carries a mapped error and passes through
 * unchanged; anything unrecognized becomes [ApiErrorCode.INTERNAL_ERROR] without leaking the class name.
 */
fun Throwable.toApiError(): ApiErrorBody = when (this) {
    is org.blaze.download.api.DownloadApiException -> error
    else -> ApiErrorBody(ApiErrorCode.INTERNAL_ERROR, message ?: "Unexpected error")
}
