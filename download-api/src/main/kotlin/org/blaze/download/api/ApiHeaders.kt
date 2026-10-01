package org.blaze.download.api

/**
 * The request headers Blaze's local API understands for client identification.
 *
 * The bearer token proves a caller *may* talk to the service; these headers say *who* is talking, so
 * the user can be shown a meaningful name ("Blaze CLI", "My App") when a new third-party client asks
 * for access instead of an anonymous socket. They are advisory: a client that omits them is still
 * identified by a server-derived fallback, and identification is never an authorization mechanism -
 * the token gate runs first.
 */
object ApiHeaders {
    /** Stable per-client-install identifier (a UUID or similar). Keys the user's approval. */
    const val CLIENT_ID: String = "X-Blaze-Client-Id"

    /** Human-readable client name shown in the approval prompt. */
    const val CLIENT_NAME: String = "X-Blaze-Client-Name"
}
