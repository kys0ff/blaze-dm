package org.blaze.platform.files

import java.awt.Desktop
import java.nio.file.Path

/**
 * macOS file integration. Opening and browsing prefers the AWT Desktop handler and falls back
 * to `/usr/bin/open`; revealing uses `open -R`, which reveals and selects the file in Finder.
 */
class MacOsSystemFileService : SystemFileService {

    override val canOpenFiles: Boolean = true
    override val canRevealInFolder: Boolean = true
    override val canBrowseLinks: Boolean = true

    override fun openPath(path: Path): Result<Unit> =
        FileOpeners.tryDesktop(Desktop.Action.OPEN, path)?.toResult(path)
            ?: FileOpeners.launch(listOf("open", path.toString()))

    override fun revealInFolder(path: Path): Result<Unit> =
        FileOpeners.launch(listOf("open", "-R", path.toAbsolutePath().toString()))

    override fun openInBrowser(url: String): Result<Unit> =
        FileOpeners.tryDesktopBrowse(url)?.toResult(url)
            ?: FileOpeners.launch(listOf("open", url))

    private fun Boolean.toResult(subject: Any): Result<Unit> =
        if (this) Result.success(Unit)
        else Result.failure(IllegalStateException("Desktop could not handle $subject"))
}
