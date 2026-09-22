package org.blaze.download.api

import kotlinx.serialization.Serializable

/**
 * Stable, machine-readable error categories for the external API.
 *
 * Engine exceptions are mapped into these codes at the application boundary (see the service's
 * `ErrorMapper`); a client switches on [code] rather than parsing messages, and the set of codes is
 * part of the versioned contract - removing or renaming one is a breaking change.
 */
enum class ApiErrorCode {
    INVALID_REQUEST,
    DOWNLOAD_NOT_FOUND,
    ALREADY_EXISTS,
    UNSUPPORTED_SOURCE,
    INVALID_URL,
    INVALID_TORRENT,
    PERMISSION_DENIED,
    DESTINATION_UNAVAILABLE,
    NETWORK_ERROR,
    AUTH_REQUIRED,
    DOWNLOAD_FAILED,
    CONFLICT,
    RATE_LIMITED,
    PAYLOAD_TOO_LARGE,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR,
}

/**
 * The wire representation of an error, identical for every transport.
 *
 * Mirrors the documented `{ "error": { code, message, details } }` envelope. [details] carries only
 * non-sensitive, stringified context (field names, ids) - never paths outside the caller's own data
 * and never credentials.
 */
@Serializable
data class ApiErrorBody(
    val code: ApiErrorCode,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

/**
 * Thrown across the [DownloadsApi] boundary for any handled failure.
 *
 * It wraps an [ApiErrorBody] so both in-process clients (GUI) and transports (HTTP) surface the same
 * code/message. Unhandled internal errors are translated to [ApiErrorCode.INTERNAL_ERROR] rather than
 * leaking stack traces to callers.
 */
class DownloadApiException(
    val error: ApiErrorBody,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause) {
    constructor(
        code: ApiErrorCode,
        message: String,
        details: Map<String, String> = emptyMap(),
        cause: Throwable? = null,
    ) : this(ApiErrorBody(code, message, details), cause)
}
