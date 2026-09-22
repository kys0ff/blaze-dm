package org.blaze.platform.files

import java.nio.file.Path

/** Fallback for platforms (or headless sessions) with no file integration we can drive. */
class UnsupportedSystemFileService : SystemFileService {
    override val canOpenFiles: Boolean = false
    override val canRevealInFolder: Boolean = false
    override val canBrowseLinks: Boolean = false

    private val failure: Result<Nothing> =
        Result.failure(UnsupportedOperationException("File integration is not supported on this platform"))

    override fun openPath(path: Path): Result<Unit> = failure
    override fun revealInFolder(path: Path): Result<Unit> = failure
    override fun openInBrowser(url: String): Result<Unit> = failure
}
