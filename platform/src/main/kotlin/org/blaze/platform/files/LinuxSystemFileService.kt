package org.blaze.platform.files

import java.awt.Desktop
import java.nio.file.Path

/**
 * Linux file integration. Opening files and browsing links prefers the AWT Desktop handler
 * (works under most X11 sessions) and falls back to `xdg-open`. "Reveal" asks the running
 * file manager to select the file when we recognise the desktop (KDE/Dolphin, GNOME/Nautilus,
 * Nemo, Caja; Thunar and the generic fallback just open the containing folder), since there is
 * no portable "select this file" command.
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
        val selectCommand = selectCommandFor(desktopEnv, absolute)

        // Prefer the DE-native "select this file" command, but a failure there (unknown binary,
        // a file manager that rejects the flag) must not surface as a hard error: fall back to
        // simply opening the containing folder, which is the best any generic opener can do.
        if (selectCommand != null) {
            val selected = FileOpeners.launch(selectCommand)
            if (selected.isSuccess) return selected
        }
        return FileOpeners.tryDesktop(Desktop.Action.OPEN, parent)?.toResult(parent)
            ?: FileOpeners.launch(listOf("xdg-open", parent.toString()))
    }

    override fun openInBrowser(url: String): Result<Unit> =
        FileOpeners.tryDesktopBrowse(url)?.toResult(url)
            ?: FileOpeners.launch(listOf("xdg-open", url))

    private fun Boolean.toResult(subject: Any): Result<Unit> =
        if (this) Result.success(Unit)
        else Result.failure(IllegalStateException("Desktop could not handle $subject"))

    companion object {
        /**
         * The command that highlights [absolute] inside the running file manager for the given
         * desktop session, or null when no recognised "select" command exists. [desktopEnv] is the
         * raw `XDG_CURRENT_DESKTOP` value, matched case-insensitively. Note KDE's Dolphin takes
         * `--select`; kioclient has no such command.
         */
        fun selectCommandFor(desktopEnv: String, absolute: Path): List<String>? {
            val env = desktopEnv.lowercase()
            return when {
                env.contains("kde") || env.contains("plasma") ->
                    listOf("dolphin", "--select", absolute.toUri().toString())
                env.contains("gnome") || env.contains("unity") ->
                    listOf("nautilus", "--select", absolute.toString())
                env.contains("cinnamon") ->
                    listOf("nemo", "--select", absolute.toString())
                env.contains("mate") ->
                    listOf("caja", "--select", absolute.toString())
                else -> null
            }
        }
    }
}
