package org.blaze.platform.files

import java.awt.Desktop
import java.nio.file.Path

/**
 * Linux file integration. Opening files and browsing links prefers the AWT Desktop handler
 * (works under most X11 sessions) and falls back to `xdg-open`. "Reveal" asks the running
 * file server to select the file when we recognise the desktop (KDE/GNOME/Nemo/Caja; Thunar
 * and the generic fallback just open the containing folder), since there is no portable
 * "select this file" command.
 */
class LinuxSystemFileService(
    private val desktopEnv: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty()
) : SystemFileService {

    override val canOpenFiles: Boolean = true
    override val canRevealInFolder: Boolean = true
    override val canBrowseLinks: Boolean = true

    override fun openPath(path: Path): Result<Unit> =
        FileOpeners.tryDesktop(Desktop.Action.OPEN, path)?.toResult(path)
            ?: FileOpeners.launch(listOf("xdg-open", path.toString()))

    override fun revealInFolder(path: Path): Result<Unit> {
        val absolute = path.toAbsolutePath()
        val parent = absolute.parent ?: absolute
        val env = desktopEnv.lowercase()
        val selectCommand: List<String>? = when {
            env.contains("kde") || env.contains("plasma") ->
                listOf("kioclient5", "select", absolute.toUri().toString())
            env.contains("gnome") || env.contains("unity") ->
                listOf("nautilus", "--select", absolute.toString())
            env.contains("cinnamon") ->
                listOf("nemo", "--select", absolute.toString())
            env.contains("mate") ->
                listOf("caja", "--select", absolute.toString())
            else -> null
        }
        // Without a known "select" command, open the parent folder — the best xdg-open can do.
        return if (selectCommand != null) {
            FileOpeners.launch(selectCommand)
        } else {
            FileOpeners.tryDesktop(Desktop.Action.OPEN, parent)?.toResult(parent)
                ?: FileOpeners.launch(listOf("xdg-open", parent.toString()))
        }
    }

    override fun openInBrowser(url: String): Result<Unit> =
        FileOpeners.tryDesktopBrowse(url)?.toResult(url)
            ?: FileOpeners.launch(listOf("xdg-open", url))

    private fun Boolean.toResult(subject: Any): Result<Unit> =
        if (this) Result.success(Unit)
        else Result.failure(IllegalStateException("Desktop could not handle $subject"))
}
