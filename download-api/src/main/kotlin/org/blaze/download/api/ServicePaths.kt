package org.blaze.download.api

/**
 * The on-disk convention for locating a running downloader service, shared by the server that writes it
 * and the clients (CLI, browser bridge) that read it.
 *
 * Keeping the file names here - rather than duplicated on each side - is what lets a pure client find the
 * service while depending only on the public API module. [defaultStorageDirName] is the folder under the
 * user's home directory where Blaze keeps its data.
 */
object ServicePaths {
    /** Data directory name under the user's home, e.g. `~/.blaze`. */
    const val DEFAULT_STORAGE_DIR_NAME: String = ".blaze"

    /** World-readable descriptor with the host/port; written at startup, cleared at shutdown. */
    const val ENDPOINT_FILE_NAME: String = "api-endpoint.json"

    /** Owner-only (`0600`) bearer token. Never merged into [ENDPOINT_FILE_NAME]. */
    const val TOKEN_FILE_NAME: String = "api-token"

    /** Advisory lock that enforces a single service per data directory. */
    const val LOCK_FILE_NAME: String = "api.lock"

    /** The token file path a trusted client may read to authenticate against a discovered service. */
    fun tokenFileFor(storageDir: java.nio.file.Path): java.nio.file.Path =
        storageDir.resolve(TOKEN_FILE_NAME)

    fun endpointFileFor(storageDir: java.nio.file.Path): java.nio.file.Path =
        storageDir.resolve(ENDPOINT_FILE_NAME)

    fun lockFileFor(storageDir: java.nio.file.Path): java.nio.file.Path =
        storageDir.resolve(LOCK_FILE_NAME)
}
