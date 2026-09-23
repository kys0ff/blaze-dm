package org.blaze.platform.desktop

import java.nio.file.Path
import java.nio.file.Paths
import org.blaze.platform.api.PlatformIdentity

/**
 * Where the user-level desktop files for [identity] live: the XDG menu/launcher entry and the icon
 * published beside it.
 *
 * This is the single source of truth for "which files does the app own", so the installer, the
 * stale-registration repair and the uninstall cleanup can never disagree about a path and start
 * deleting each other's work. System-wide locations (`/usr/share/applications`, everything under a
 * package manager's control) are intentionally absent: Blaze only ever writes - and therefore only
 * ever removes - under the user's own XDG dirs.
 */
class DesktopRegistration(
    val identity: PlatformIdentity,
    val desktopDir: Path = defaultDesktopDir(),
    val iconDir: Path = defaultIconDir(),
) {
    /** `~/.local/share/applications/<appId>.desktop`, the entry [LauncherEntryInstaller] writes. */
    val entryFile: Path get() = desktopDir.resolve(identity.desktopFileName)

    /** The icon published into the hicolor theme, keeping the configured resource's extension. */
    val iconFile: Path get() = iconDir.resolve("${identity.appId}.${iconExtension()}")

    /**
     * Whether an entry [identity] owns by name is actually *ours* to remove: [PlatformIdentity]'s file
     * name plus the window class only our launcher produces. Anything else that happens to sit at the
     * path (a third-party or hand-written entry) is left strictly alone.
     */
    fun isOurs(entry: DesktopEntry): Boolean = entry.startupWmClass == identity.windowManagerClass

    /** File extension to publish under, taken from the configured icon resource. */
    private fun iconExtension(): String =
        identity.iconResource?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: "png"

    companion object {
        /** XDG spec: per-user application entries. */
        fun defaultDesktopDir(): Path =
            Paths.get(System.getProperty("user.home"), ".local", "share", "applications")

        /** Scalable icons go here; entries reference them by absolute path to bypass theme caching. */
        fun defaultIconDir(): Path =
            Paths.get(System.getProperty("user.home"), ".local", "share", "icons", "hicolor", "scalable", "apps")
    }
}
