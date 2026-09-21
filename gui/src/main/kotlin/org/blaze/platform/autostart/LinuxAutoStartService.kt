package org.blaze.platform.autostart

import java.nio.file.Files
import java.nio.file.Path

/**
 * XDG desktop-entry autostart: writes/removes
 * `~/.config/autostart/org.blaze.desktop`, honoured by GNOME, KDE, XFCE and friends.
 * The autostart directory is injectable so the file handling is testable anywhere.
 */
class LinuxAutoStartService(
    private val autostartDir: Path = Path.of(System.getProperty("user.home"), ".config", "autostart"),
    private val execCommand: () -> String = {
        ProcessHandle.current().info().command().orElse("blaze")
    }
) : AutoStartService {

    private val desktopFile = autostartDir.resolve("org.blaze.desktop")

    override val isSupported: Boolean = true

    override fun isEnabled(): Boolean = Files.exists(desktopFile)

    override fun enable(): Result<Unit> = runCatching {
        Files.createDirectories(autostartDir)
        Files.writeString(desktopFile, desktopEntry(execCommand()))
    }

    override fun disable(): Result<Unit> = runCatching {
        Files.deleteIfExists(desktopFile)
    }.map { }

    private fun desktopEntry(exec: String): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Name=Blaze")
        appendLine("Comment=Blaze download manager")
        appendLine("Exec=$exec")
        appendLine("Terminal=false")
        appendLine("X-GNOME-Autostart-enabled=true")
    }
}
