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
    private val iconDir: Path = defaultIconDir(),
    private val iconBytes: () -> ByteArray? = iconResourceLoader(identity.iconResource),
    private val kServiceCacheRefresher: () -> Unit = ::refreshKServiceCache,
) {

    private val logger = LoggerFactory.getLogger(LauncherEntryInstaller::class.java)

    /**
     * Publish the icon and create or refresh the desktop entry.
     *
     * The bundled PNG is copied into a stable, persistent location the app controls and
     * the desktop entry's `Icon=` is pinned to that **absolute path** (not a themed
     * name, which needs the icon-theme cache to have been rebuilt, and not the ephemeral
     * `/opt` install path, which disappears on dev runs / after an uninstall). A packaged
     * launch writes the entry as before; a plain `gradle run` writes no new entry, yet
     * repairs a stale one left over from an earlier install whose `Icon=` path has since
     * gone missing - otherwise Plasma matches that dead entry to the window (via
     * `StartupWMClass`) and blanks the taskbar icon in favour of the window's own
     * `_NET_WM_ICON`.
     */
    fun ensureInstalled() {
        val iconPath = installIconFile()
        val launcher = launcherCommand?.let { parsePackagedLauncher(it) }

        if (launcher != null) {
            installPackagedEntry(launcher, iconPath)
        } else {
            repairExistingEntryIcon(iconPath)
        }
    }

    private fun installPackagedEntry(launcher: PackagedLauncher, iconPath: String?) {
        val content = desktopEntryContent(launcher, iconPath)
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

    /**
     * Re-point an existing desktop entry's `Icon=` at [iconPath]. Only touches an entry
     * that is already present (so dev runs never create a fresh menu item) and whose icon
     * does not already point at [iconPath].
     */
    private fun repairExistingEntryIcon(iconPath: String?) {
        if (iconPath == null) return
        val target = desktopDir.resolve(identity.desktopFileName)
        runCatching {
            if (!Files.exists(target)) return
            val existing = Files.readString(target)
            val repaired = setIconLine(existing, iconPath)
            if (repaired == existing) return
            Files.writeString(target, repaired)
            logger.info("Repointed the existing desktop entry icon to {}", iconPath)
            kServiceCacheRefresher()
        }.onFailure { logger.warn("Could not repair the desktop entry at {}", target, it) }
    }

    /** Replace (or insert) the desktop entry's `Icon=` key with `Icon=[icon]`. */
    private fun setIconLine(content: String, icon: String): String {
        val line = "Icon=$icon"
        val regex = Regex("(?m)^Icon=.*$")
        return if (regex.containsMatchIn(content)) {
            regex.replace(content, line)
        } else {
            buildString { append(content.trimEnd()).append('\n').append(line).append('\n') }
        }
    }

    /**
     * Copy the bundled PNG into a stable location and return its absolute path for the
     * desktop entry to reference. Returns null when no asset is configured or the copy
     * fails, letting callers fall back to the packaging layout's own icon path.
     */
    private fun installIconFile(): String? {
        val bytes = runCatching { iconBytes() }.getOrNull() ?: return null
        val target = iconDir.resolve("${identity.appId}.png")
        val installed = runCatching {
            if (!Files.exists(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
                Files.createDirectories(iconDir)
                Files.write(target, bytes)
            }
            true
        }.getOrElse {
            logger.warn("Could not install the launcher icon at {}", target, it)
            false
        }
        return if (installed) target.toAbsolutePath().toString() else null
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

    private fun desktopEntryContent(launcher: PackagedLauncher, iconPath: String?): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Version=1.0")
        appendLine("Name=${identity.appName}")
        appendLine("GenericName=${identity.genericName}")
        appendLine("Comment=${identity.genericName}")
        appendLine("Exec=${launcher.executable}")
        // Prefer the stable absolute path we just published; fall back to the packaging
        // layout's own PNG only when that could not be written.
        when {
            iconPath != null -> appendLine("Icon=$iconPath")
            Files.exists(launcher.icon) -> appendLine("Icon=${launcher.icon}")
        }
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

        /** A conventional hicolor size dir; the themed lookup finds it via any size. */
        private fun defaultIconDir(): Path =
            Paths.get(System.getProperty("user.home"), ".local", "share", "icons", "hicolor", "256x256", "apps")

        /** Reads the identity's bundled icon PNG from the classpath (null when unset). */
        private fun iconResourceLoader(resource: String?): () -> ByteArray? = {
            resource?.let { path ->
                runCatching {
                    LauncherEntryInstaller::class.java.getResourceAsStream(path)?.use { it.readAllBytes() }
                }.getOrNull()
            }
        }

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
