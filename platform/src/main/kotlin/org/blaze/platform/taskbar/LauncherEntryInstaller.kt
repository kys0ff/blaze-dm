package org.blaze.platform.taskbar

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.blaze.platform.api.PlatformIdentity
import org.slf4j.LoggerFactory

/**
 * Installs the user-level `~/.local/share/applications/<identity.desktopFileName>`
 * entry that [LinuxTaskbarProgressService]'s LauncherEntry broadcasts are resolved
 * against.
 *
 * This is required because jpackage does **not** give us a usable entry out of the box:
 * - its bundled `.desktop` is only installed to the system with `--linux-shortcut`
 *   (Compose's `linux { shortcut }` defaults to `false`), so a plain package run leaves
 *   Plasma unable to find *any* service for `application://<app>.desktop` (it logs
 *   `Failed to find service for Unity Launcher ...` and drops the progress update); and
 * - the generated entry never contains `StartupWMClass`, so even when installed the
 *   running window (whose `WM_CLASS` the JDK derives from the main class name, e.g.
 *   `org-blaze-MainKt`) would not be matched to the launcher.
 *
 * Writing our own entry under the XDG user dir fixes both at once - the same approach
 * Chromium-based browsers use. The entry is only written when the process actually runs
 * from a jpackage layout (`<app>/bin/<launcher>` + `<app>/lib`), so plain `gradle run`
 * sessions leave no stray menu entries; dev windows still get progress as soon as the
 * packaged app has been started once, because `StartupWMClass` matches either way.
 */
class LauncherEntryInstaller(
    private val identity: PlatformIdentity,
    private val desktopDir: Path = defaultDesktopDir(),
    private val launcherCommand: String? = defaultLauncherCommand(),
    private val kServiceCacheRefresher: () -> Unit = ::refreshKServiceCache,
) {

    private val logger = LoggerFactory.getLogger(LauncherEntryInstaller::class.java)

    /**
     * Creates or refreshes the desktop entry. Quietly does nothing when this run is not
     * a packaged app launch or the entry is already up to date.
     */
    fun ensureInstalled() {
        val command = launcherCommand ?: return
        val launcher = parsePackagedLauncher(command) ?: return

        val content = desktopEntryContent(launcher)
        val target = desktopDir.resolve(identity.desktopFileName)
        runCatching {
            if (Files.exists(target) && Files.readString(target) == content) return
            Files.createDirectories(desktopDir)
            Files.writeString(target, content)
            logger.info("Installed the LauncherEntry desktop file at {}", target)
            // KDE resolves launcher URIs through the KService cache; nudge it so the
            // very first progress update already resolves.
            kServiceCacheRefresher()
        }.onFailure { logger.warn("Could not install the desktop entry at {}", target, it) }
    }

    /** Pure jpackage-layout sniffing: `<app>/bin/<name>` above an `<app>/lib/app` tree. */
    private fun parsePackagedLauncher(command: String): PackagedLauncher? {
        val executable = Paths.get(command)
        val binDir = executable.parent ?: return null
        if (binDir.fileName.toString() != "bin") return null
        if (!Files.isRegularFile(executable)) return null
        val appDir = binDir.parent ?: return null
        // jpackage ships the application jars in <app>/lib/app; this marker keeps
        // unrelated bin/lib pairs (e.g. /usr/bin/java) from looking like a launch image.
        if (!Files.isDirectory(appDir.resolve("lib").resolve("app"))) return null
        return PackagedLauncher(
            executable = executable,
            appDir = appDir,
            icon = appDir.resolve("lib").resolve(executable.fileName.toString() + ".png"),
        )
    }

    private fun desktopEntryContent(launcher: PackagedLauncher): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Version=1.0")
        appendLine("Name=${identity.appName}")
        appendLine("GenericName=${identity.genericName}")
        appendLine("Comment=${identity.genericName}")
        appendLine("Exec=${launcher.executable}")
        if (Files.exists(launcher.icon)) appendLine("Icon=${launcher.icon}")
        appendLine("Terminal=false")
        appendLine("Categories=${identity.categories}")
        // java.awt on X11 builds WM_CLASS from the main class name with dots replaced
        // by dashes, which is exactly how identity.windowManagerClass is derived.
        appendLine("StartupWMClass=${identity.windowManagerClass}")
    }

    private class PackagedLauncher(
        val executable: Path,
        val appDir: Path,
        val icon: Path,
    )

    private companion object {
        private fun defaultDesktopDir(): Path =
            Paths.get(System.getProperty("user.home"), ".local", "share", "applications")

        private fun defaultLauncherCommand(): String? =
            ProcessHandle.current().info().command().orElse(null)

        /** Best-effort KService rebuild; a no-op on non-KDE systems without the tool. */
        private fun refreshKServiceCache() {
            runCatching {
                ProcessBuilder("kbuildsycoca6")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(15, TimeUnit.SECONDS)
            }
        }
    }
}
