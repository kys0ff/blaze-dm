package org.blaze.download.api

import kotlinx.serialization.Serializable

/**
 * Where a running downloader service can be found, so a client (CLI, browser-bridge) can connect.
 *
 * The service writes this to a well-known, user-owned file at startup and removes it on shutdown. It
 * intentionally does **not** contain the authentication token - the token lives in a separate file with
 * owner-only (`0600`) permissions and is never serialized into this record, so a world-readable
 * discovery file never leaks the secret.
 */
@Serializable
data class ServiceDescriptor(
    val host: String,
    val port: Int,
    val apiVersion: Int,
    val pid: Long,
    val startedAtEpochMs: Long,
    /** Absolute path of the file holding the bearer token, for trusted clients that may read it. */
    val tokenFile: String,
) {
    val baseUrl: String get() = "http://$host:$port"
}
