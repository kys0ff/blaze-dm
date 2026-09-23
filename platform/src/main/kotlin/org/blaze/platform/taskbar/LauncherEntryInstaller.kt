package org.blaze.platform.taskbar

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.blaze.platform.api.PlatformIdentity
import org.blaze.platform.desktop.DesktopEntry
import org.blaze.platform.desktop.DesktopRegistration
import org.blaze.platform.desktop.LaunchCommand
import org.slf4j.LoggerFactory

/**
 * Owns the user-level `~/.local/share/applications/<identity.desktopFileName>` entry that
 * [LinuxTaskbarProgressService]'s LauncherEntry broadcasts are resolved against.
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
 *
 * The same object keeps the registration *honest* over time, because everything it writes lives
 * under the user's home and therefore outlives both the debug session that produced it and the
 * package that installed it: [ensureInstalled] prunes a dead entry (uninstall / moved install
 * residue) and never publishes an icon nothing references, while [uninstall] removes every trace on
 * request. Files that are not ours - a hand-written or third-party entry at the same path - are
 * never touched.
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

    private val registration = DesktopRegistration(identity, desktopDir, iconDir)

    /**
     * Make the desktop registration match the way the app is running *right now*, and clean up what
     * no longer matches:
     *
     * 1. prune a dead entry [identity] owns (its `Exec` launcher was uninstalled or moved) together
     *    with the icon it leaves orphaned - such an entry is a menu item that fails on click and a
     *    `magnet:` claim that breaks the desktop's handler resolution;
     * 2. a packaged launch publishes the icon and creates or refreshes the entry;
     * 3. a plain `gradle run` writes no new entry, yet repairs a live one whose `Icon=` path has since
     *    gone missing - otherwise Plasma matches that dead icon (via `StartupWMClass`) and blanks the
     *    taskbar icon in favour of the window's own `_NET_WM_ICON`. The icon asset is copied *only*
     *    when an entry will actually reference it, so debugging leaves no orphan behind.
     *
     * Returns the files removed along the way, so the caller can report what changed.
     */
    fun ensureInstalled(): List<Path> {
        val removed = pruneDeadEntry().toMutableList()
        val launcher = launcherCommand?.let { parsePackagedLauncher(it) }

        if (launcher != null) {
            val iconPath = installIconFile()
            installPackagedEntry(launcher, iconPath)
        } else {
            repairExistingEntryIcon()
        }
        return removed
    }

    /**
     * Removes everything this app may have published at the user level - the desktop entry (when it is
     * ours) and the icon once nothing references it. This is the uninstall/cleanup counterpart of
     * [ensureInstalled]; it never touches files Blaze did not write and never deletes the user's data.
     */
    fun uninstall(): List<Path> {
        val removed = mutableListOf<Path>()
        val entry = DesktopEntry.read(registration.entryFile)
        when {
            entry == null -> Unit // nothing installed (or unreadable): nothing to remove
            registration.isOurs(entry) -> runCatching { Files.deleteIfExists(registration.entryFile) }
                .onSuccess { removed.add(registration.entryFile) }
                .onFailure { logger.warn("Could not remove the desktop entry at {}", registration.entryFile, it) }
            else -> logger.info(
                "Keeping {}: it carries StartupWMClass={} rather than this app's ({})",
                registration.entryFile, entry.startupWmClass, identity.windowManagerClass,
            )
        }
        removed.addAll(removeOrphanedIcons())
        if (removed.isNotEmpty()) kServiceCacheRefresher()
        return removed
    }

    private fun installPackagedEntry(launcher: PackagedLauncher, iconPath: String?) {
        val content = desktopEntryContent(launcher, iconPath)
        val target = registration.entryFile
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
     * Re-points an existing desktop entry's `Icon=` at the published icon. Only ever touches an entry
     * that is already present (dev runs never create a fresh menu item) and whose icon does not already
     * resolve to it - and only publishes the icon when that repair is actually needed.
     */
    private fun repairExistingEntryIcon() {
        val target = registration.entryFile
        val existing = DesktopEntry.read(target) ?: return
        val intended = registration.iconFile.toAbsolutePath().normalize().toString()
        if (existing.iconPath()?.toAbsolutePath()?.normalize()?.toString() == intended) return

        val iconPath = installIconFile() ?: return
        val repaired = existing.withIcon(iconPath)
        if (repaired.content() == existing.content()) return
        runCatching {
            Files.writeString(target, repaired.content())
            logger.info("Repointed the existing desktop entry icon to {}", iconPath)
            kServiceCacheRefresher()
        }.onFailure { logger.warn("Could not repair the desktop entry at {}", target, it) }
    }

    /**
     * Deletes a desktop entry Blaze wrote whose launcher no longer exists.
     *
     * A leftover entry is actively hostile to the desktop: it keeps claiming `x-scheme-handler/magnet`
     * and `application/x-bittorrent`, so a torrent click after an uninstall resolves to a program that
     * is gone and fails instead of falling back to whatever the user picks. Removal is deliberately
     * narrow - our file name, our `StartupWMClass`, and an absolute `Exec` under a stable root that is
     * really missing (an AppImage launcher under a FUSE mount legitimately vanishes between runs).
     */
    private fun pruneDeadEntry(): List<Path> {
        val entry = DesktopEntry.read(registration.entryFile) ?: return emptyList()
        if (!registration.isOurs(entry)) return emptyList()
        val exec = entry.key("Exec") ?: return emptyList()
        if (!LaunchCommand.isMissingInstall(exec)) return emptyList()

        logger.info(
            "Removing the desktop entry at {}: its launcher {} no longer exists (uninstalled or moved).",
            registration.entryFile, exec,
        )
        val removed = runCatching { Files.deleteIfExists(registration.entryFile) }
            .onFailure { logger.warn("Could not remove the dead desktop entry at {}", registration.entryFile, it) }
            .getOrDefault(false)
        val files = mutableListOf<Path>()
        if (removed) files.add(registration.entryFile)
        files.addAll(removeOrphanedIcons())
        if (files.isNotEmpty()) kServiceCacheRefresher()
        return files
    }

    /**
     * Copies the bundled icon into a stable location and returns its absolute path for the desktop
     * entry to reference. The installed file keeps the source extension (e.g. `svg`/`png`). Returns
     * null when no asset is configured or the copy fails, letting callers fall back to the packaging
     * layout's own icon path.
     */
    private fun installIconFile(): String? {
        val bytes = runCatching { iconBytes() }.getOrNull() ?: return null
        val target = registration.iconFile
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

    /**
     * Deletes the launcher icons this app published into [iconDir] that no installed entry pins any
     * more, and returns what went.
     *
     * Ownership is decided by *name* rather than by the one extension the current build happens to
     * ship: the directory (`~/.local/share/icons/hicolor/scalable/apps`) is where this app puts its
     * icon and nothing else, so `<appId>.<short-ext>` is unambiguous authorship. That also collects an
     * orphan left by an older build that published a different format - an icon no `.desktop` refers
     * to any more is a theme entry nothing can point at.
     */
    private fun removeOrphanedIcons(): List<Path> {
        if (!Files.isDirectory(iconDir)) return emptyList()
        val candidates = runCatching {
            Files.list(iconDir).use { entries ->
                entries.filter { isOwnedIconName(it.fileName.toString()) }.toList()
            }
        }.getOrElse {
            logger.warn("Could not inspect {} for orphaned launcher icons", iconDir, it)
            emptyList()
        }
        if (candidates.isEmpty()) return emptyList()
        val declared = readIconDeclarations()
        return candidates.filterNot { declared.references(it) }.filter { path ->
            runCatching { Files.deleteIfExists(path) }
                .onSuccess { logger.info("Removed the orphaned launcher icon {}", path) }
                .onFailure { logger.warn("Could not remove the orphaned launcher icon at {}", path, it) }
                .getOrDefault(false)
        }
    }

    /** `<appId>.<ext>` with a plausible short extension - the names this app can have written. */
    private fun isOwnedIconName(name: String): Boolean {
        val prefix = "${identity.appId}."
        if (!name.startsWith(prefix)) return false
        val extension = name.substringAfter(prefix)
        return extension.length in 1..5 && extension.all { it.isLetterOrDigit() }
    }

    /**
     * The `Icon=` values declared by the entries in [desktopDir], and whether they could all be read.
     *
     * An unreadable directory is reported as incomplete, and then nothing may be deleted: failing open
     * costs one stale image, failing closed would remove an icon a live entry still points at.
     */
    private fun readIconDeclarations(): IconDeclarations {
        val values = runCatching {
            Files.list(desktopDir).use { entries ->
                entries
                    .filter { it.fileName.toString().endsWith(".desktop") }
                    // An entry we cannot read might still name the icon, so it counts as a declaration.
                    .map { file -> DesktopEntry.read(file)?.key("Icon")?.takeIf { it.isNotBlank() } ?: UNKNOWN_ICON }
                    .toList()
            }
        }.getOrElse {
            logger.warn("Could not read the desktop entries in {}", desktopDir, it)
            return IconDeclarations(values = listOf(UNKNOWN_ICON), complete = false)
        }
        return IconDeclarations(values = values, complete = true)
    }

    /** The declared icons of the installed entries; see [readIconDeclarations]. */
    private class IconDeclarations(private val values: List<String>, private val complete: Boolean) {
        /**
         * Whether any entry points at [icon] - by absolute path, or by *themed name*, which is how a
         * packaging-generated entry names its icon (`Icon=org.blaze`). A themed reference is exactly as
         * binding as a path: the icon theme resolves it to the file in the icon directory. The `png`
         * spelling is included because that is the extension jpackage's own entry writes.
         */
        fun references(icon: Path): Boolean {
            if (!complete) return true
            val wanted = icon.toAbsolutePath().normalize()
            val fileName = icon.fileName.toString()
            val stem = fileName.substringBeforeLast('.', fileName)
            return values.any { raw ->
                if (raw.contains('/') || raw.contains('\\')) {
                    runCatching { Paths.get(raw).toAbsolutePath().normalize() == wanted }.getOrDefault(false)
                } else {
                    raw == UNKNOWN_ICON ||
                        raw == fileName || raw == stem || raw == "$stem.png"
                }
            }
        }
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
        // `%F` receives the file URLs a file manager/xdg-open passes (`blaze file.torrent`);
        // plain menu launches simply drop the field code per the desktop-entry spec. The
        // executable path is quoted so install locations containing spaces survive parsing.
        appendLine("Exec=\"${launcher.executable}\" %F")
        // Prefer the stable absolute path we just published; fall back to the packaging
        // layout's own PNG only when that could not be written.
        when {
            iconPath != null -> appendLine("Icon=$iconPath")
            Files.exists(launcher.icon) -> appendLine("Icon=${launcher.icon}")
        }
        appendLine("Terminal=false")
        appendLine("Categories=${identity.categories}")
        // OS-integration declarations: opening a .torrent or a magnet: link resolves to this
        // entry via xdg-mime/xdg-open (`x-scheme-handler/magnet` is the DE-standard way to
        // claim a URL scheme). The launcher turns these arguments into ExternalOpenRequests.
        appendLine("MimeType=application/x-bittorrent;x-scheme-handler/magnet;")
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
        /** Stands for an icon declaration that could not be read; keeps every icon alive. */
        private const val UNKNOWN_ICON = "*"

        private fun defaultDesktopDir(): Path = DesktopRegistration.defaultDesktopDir()

        /** Scalable icons (e.g. SVG) go here; the absolute path bypasses theme caching. */
        private fun defaultIconDir(): Path = DesktopRegistration.defaultIconDir()

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
