package org.blaze.platform.files

import java.awt.Desktop
import java.nio.file.Path

/**
 * Windows file integration. Opening and browsing prefers the AWT Desktop handler and falls
 * back to `cmd /c start`; revealing uses `explorer /select,<path>`, which opens the parent
 * folder with the file highlighted.
 */
class WindowsSystemFileService : SystemFileService {

    override val canOpenFiles: Boolean = true
    override val canRevealInFolder: Boolean = true
    override val canBrowseLinks: Boolean = true

    override fun openPath(path: Path): Result<Unit> =
        FileOpeners.tryDesktop(Desktop.Action.OPEN, path)?.toResult(path)
            ?: FileOpeners.launch(listOf("cmd", "/c", "start", "", path.toString()))

    override fun revealInFolder(path: Path): Result<Unit> {
        val absolute = path.toAbsolutePath().toString()
        // Explorer wants a single "/select,<path>" token with backslash separators.
        return FileOpeners.launch(listOf("explorer", "/select,$absolute"))
            .recoverCatching {
                // Explorer returns non-zero even on success in some shells; fall back to opening the folder.
                val parent = path.toAbsolutePath().parent?.toString() ?: absolute
                FileOpeners.launch(listOf("explorer", parent)).getOrThrow()
            }
    }

    override fun openInBrowser(url: String): Result<Unit> =
        FileOpeners.tryDesktopBrowse(url)?.toResult(url)
            ?: FileOpeners.launch(listOf("cmd", "/c", "start", "", url))

    private fun Boolean.toResult(subject: Any): Result<Unit> =
        if (this) Result.success(Unit)
        else Result.failure(IllegalStateException("Desktop could not handle $subject"))
}
