package org.blaze.platform.autostart

import org.blaze.platform.api.PlatformIdentity
import org.blaze.platform.desktop.DesktopEntry
import java.nio.file.Files
import java.nio.file.Path

/**
 * XDG desktop-entry autostart: writes/removes
 * `~/.config/autostart/<identity.desktopFileName>`, honoured by GNOME, KDE, XFCE and
 * friends. The autostart directory is injectable so the file handling is testable anywhere.
 *
 * [enable] refuses a command the session could not launch on its own (a bare JVM from a debug run),
 * and [registrationCommand] hands the recorded `Exec` back so a leftover entry whose program has gone
 * away can be pruned instead of erroring at every sign-in.
 */
class LinuxAutoStartService(
    private val identity: PlatformIdentity,
    private val autostartDir: Path = Path.of(System.getProperty("user.home"), ".config", "autostart"),
    private val execCommand: () -> String = {
        ProcessHandle.current().info().command().orElse(identity.executableName)
    }
) : AutoStartService {

    private val desktopFile = autostartDir.resolve(identity.desktopFileName)

    override val isSupported: Boolean = true

    override fun isEnabled(): Boolean = Files.exists(desktopFile)

    override fun registrationCommand(): String? =
        DesktopEntry.read(desktopFile)?.key("Exec")

    override fun enable(): Result<Unit> = runCatching {
        val exec = requireLaunchableLoginItem(execCommand())
        Files.createDirectories(autostartDir)
        Files.writeString(desktopFile, desktopEntry(exec))
    }

    override fun disable(): Result<Unit> = runCatching {
        Files.deleteIfExists(desktopFile)
    }.map { }

    private fun desktopEntry(exec: String): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Name=${identity.appName}")
        appendLine("Comment=${identity.description}")
        appendLine("Exec=$exec")
        appendLine("Terminal=false")
        appendLine("X-GNOME-Autostart-enabled=true")
    }
}
