package org.blaze.download.api

/**
 * Versioning of the *external* download API.
 *
 * The version is part of the wire contract and is exposed on every transport (HTTP path segment,
 * capabilities payload, service descriptor). It is bumped only for breaking changes to request/
 * response shapes, state names or error codes - never for internal engine refactors. A client that
 * pins to [CURRENT] is insulated from everything below the [DownloadsApi] boundary.
 */
object ApiVersion {
    /** The version this build implements. */
    const val CURRENT: Int = 1

    /** URL segment used by HTTP-style transports, e.g. `/api/v1/downloads`. */
    const val SEGMENT: String = "v1"

    /** Base path prefix for HTTP transports. */
    const val BASE_PATH: String = "/api/$SEGMENT"
}
