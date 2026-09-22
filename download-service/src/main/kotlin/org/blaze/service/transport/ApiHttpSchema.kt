package org.blaze.service.transport

import kotlinx.serialization.Serializable
import org.blaze.download.api.ApiErrorCode
import org.blaze.download.api.DownloadApiException
import org.blaze.download.api.DownloadSource
import org.blaze.download.api.NewDownloadRequest
import org.blaze.download.api.TransferHeaders

/**
 * The wire body accepted by `POST /api/v1/downloads`, shaped for what a browser extension can easily
 * produce: a flat object with exactly one of [url] / [magnet] / [torrentFile], rather than a nested
 * polymorphic structure.
 *
 * It is a transport concern only - the domain never sees it; [toRequest] translates it into the stable
 * [NewDownloadRequest]. [credentials] carries short-lived auth material that is forwarded to the engine's
 * in-memory request and is never persisted, echoed in a response, or logged.
 */
@Serializable
data class CreateDownloadHttp(
    val url: String? = null,
    val magnet: String? = null,
    val torrentFile: String? = null,
    val destination: String,
    val filename: String? = null,
    val autoStart: Boolean = true,
    val connections: Int? = null,
    val bandwidthLimitBytesPerSecond: Long? = null,
    val expectedSizeBytes: Long? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val accept: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val credentials: Map<String, String> = emptyMap(),
    val idempotencyKey: String? = null,
) {
    fun toRequest(): NewDownloadRequest {
        val source = listOfNotNull(
            url?.let { DownloadSource.Url(it) },
            magnet?.let { DownloadSource.Magnet(it) },
            torrentFile?.let { DownloadSource.TorrentFile(it) },
        )
        if (source.size != 1) {
            throw DownloadApiException(
                ApiErrorCode.INVALID_REQUEST,
                "exactly one of url, magnet or torrentFile is required",
            )
        }
        return NewDownloadRequest(
            source = source.first(),
            destination = destination,
            filename = filename,
            autoStart = autoStart,
            connections = connections,
            bandwidthLimitBytesPerSecond = bandwidthLimitBytesPerSecond,
            expectedSizeBytes = expectedSizeBytes,
            transferHeaders = TransferHeaders(
                userAgent = userAgent,
                referer = referer,
                accept = accept,
                additional = headers,
            ),
            credentials = credentials,
            idempotencyKey = idempotencyKey,
        )
    }
}

/** Minimal success envelope used where a full DTO is not needed (lifecycle verbs). */
@Serializable
data class OkHttp(val ok: Boolean = true)

/** The `{ "error": { ... } }` envelope every failure is serialized into. */
@Serializable
data class ErrorEnvelope(val error: org.blaze.download.api.ApiErrorBody)
