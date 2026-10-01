package org.blaze.download.api

import org.blaze.download.api.ServicePaths.ENDPOINT_FILE_NAME
import java.nio.file.Path

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

    /** Lock held by the one GUI process allowed to run against this data directory. */
    const val INSTANCE_LOCK_FILE_NAME: String = "instance.lock"

    /** Local IPC socket the owning GUI listens on to receive external-open requests. */
    const val INSTANCE_SOCKET_FILE_NAME: String = "instance.sock"

    /** Owner-only (`0600`) shared secret for the single-instance IPC channel. */
    const val INSTANCE_TOKEN_FILE_NAME: String = "instance-token"

    /** Port file for the loopback-TCP fallback when the platform has no unix-socket support. */
    const val INSTANCE_TCP_FILE_NAME: String = "instance-tcp"

    /**
     * Stable identity a first-party client (the CLI) mints once and reuses, so the user approves
     * "Blaze CLI" the first time instead of a fresh anonymous id on every run. Not a secret: it only
     * keys consent prompts, it authorizes nothing - the bearer token still has to be right.
     */
    const val CLIENT_ID_FILE_NAME: String = "cli-client-id"

    /** The token file path a trusted client may read to authenticate against a discovered service. */
    fun tokenFileFor(storageDir: Path): Path =
        storageDir.resolve(TOKEN_FILE_NAME)

    /** Where a pure client stores its stable identity for this data directory. */
    fun clientIdFileFor(storageDir: Path): Path =
        storageDir.resolve(CLIENT_ID_FILE_NAME)

    fun endpointFileFor(storageDir: Path): Path =
        storageDir.resolve(ENDPOINT_FILE_NAME)

    fun lockFileFor(storageDir: Path): Path =
        storageDir.resolve(LOCK_FILE_NAME)

    fun instanceLockFileFor(storageDir: Path): Path =
        storageDir.resolve(INSTANCE_LOCK_FILE_NAME)

    fun instanceSocketFileFor(storageDir: Path): Path =
        storageDir.resolve(INSTANCE_SOCKET_FILE_NAME)

    fun instanceTokenFileFor(storageDir: Path): Path =
        storageDir.resolve(INSTANCE_TOKEN_FILE_NAME)

    fun instanceTcpFileFor(storageDir: Path): Path =
        storageDir.resolve(INSTANCE_TCP_FILE_NAME)

    /** The default data directory under the user's home, e.g. `~/.blaze`. */
    fun defaultStorageDir(): Path =
        Path.of(System.getProperty("user.home"), DEFAULT_STORAGE_DIR_NAME)
}
