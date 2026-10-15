package org.blaze.platform.files

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows file integration. Opening and browsing prefers the AWT Desktop handler and falls
 * back to `cmd /c start`; revealing uses `explorer /select,<path>`, which opens the parent
 * folder with the file highlighted. Both explorer calls are fire-and-forget because explorer
 * reports a non-zero exit code even when it has shown the window.
 */
class WindowsSystemFileService : SystemFileService {

    override val canOpenFiles: Boolean = true
    override val canRevealInFolder: Boolean = true
    override val canBrowseLinks: Boolean = true

    override fun openPath(path: Path): Result<Unit> =
        FileOpeners.tryDesktop(Desktop.Action.OPEN, path)?.toResult(path)
            ?: FileOpeners.launch(listOf("cmd", "/c", "start", "", path.toString()))

    override fun revealInFolder(path: Path): Result<Unit> {
        val absolute = path.toAbsolutePath().normalize()
        // Explorer wants a single "/select,<path>" token with backslash separators. The status is
        // deliberately unchecked and there is no retry: explorer exits non-zero even on success, and
        // a second launch is what used to open a duplicate window for the same file.
        if (Files.exists(absolute)) {
            return FileOpeners.launchDetached(listOf("explorer", "/select,$absolute"))
        }
        // The file has since been moved or deleted, so there is nothing to highlight. Still land the
        // user in the folder it came from rather than failing on a launch that would have "succeeded".
        val parent = absolute.parent?.takeIf { Files.isDirectory(it) }
            ?: return Result.failure(IllegalStateException("File no longer exists at $absolute"))
        return FileOpeners.launchDetached(listOf("explorer", parent.toString()))
    }

    override fun openInBrowser(url: String): Result<Unit> =
        FileOpeners.tryDesktopBrowse(url)?.toResult(url)
            ?: FileOpeners.launch(listOf("cmd", "/c", "start", "", url))

    private fun Boolean.toResult(subject: Any): Result<Unit> =
        if (this) Result.success(Unit)
        else Result.failure(IllegalStateException("Desktop could not handle $subject"))
}
