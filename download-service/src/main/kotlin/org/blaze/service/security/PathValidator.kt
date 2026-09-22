package org.blaze.service.security

import org.blaze.download.api.ApiErrorCode
import org.blaze.download.api.DownloadApiException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Confirms a client-supplied destination is safe to write to.
 *
 * A trusted in-process caller (the GUI) may pass [allowedRoots] = null and is only checked for obvious
 * nonsense (blank path, embedded NUL). An *untrusted* transport - the loopback HTTP API a browser bridge
 * could reach - configures a set of roots the download must stay inside, which is what stops a hostile
 * page from using the downloader to write to `~/.ssh/authorized_keys` or a startup folder.
 *
 * The check runs on the normalized absolute path so `..` traversal tricks collapse to the same real
 * target before comparison.
 */
class PathValidator(private val allowedRoots: List<Path>? = null) {

    /**
     * Validates [rawDestination] and returns it as an absolute, normalized path.
     *
     * @throws DownloadApiException [ApiErrorCode.INVALID_REQUEST] when blank / contains a NUL, or
     *   [ApiErrorCode.PERMISSION_DENIED] when it escapes a configured root.
     */
    fun validateDirectory(rawDestination: String): Path {
        if (rawDestination.isBlank()) {
            throw DownloadApiException(ApiErrorCode.INVALID_REQUEST, "destination must not be blank")
        }
        if ('\u0000' in rawDestination) {
            throw DownloadApiException(ApiErrorCode.INVALID_REQUEST, "destination contains a null byte")
        }

        val path = try {
            Paths.get(rawDestination).toAbsolutePath().normalize()
        } catch (e: Exception) {
            throw DownloadApiException(ApiErrorCode.INVALID_REQUEST, "destination is not a valid path", cause = e)
        }

        val roots = allowedRoots
        if (roots != null && roots.none { path.startsWith(it) }) {
            throw DownloadApiException(
                ApiErrorCode.PERMISSION_DENIED,
                "destination is outside the allowed download roots",
                details = mapOf("reason" to "confined"),
            )
        }
        return path
    }

    companion object {
        /** A permissive validator for trusted, in-process callers. */
        val TRUSTED: PathValidator = PathValidator(allowedRoots = null)

        fun roots(vararg dirs: String): PathValidator =
            PathValidator(dirs.map { Paths.get(it).toAbsolutePath().normalize() })
    }
}
