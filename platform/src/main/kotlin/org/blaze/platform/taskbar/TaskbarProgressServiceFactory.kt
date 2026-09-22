package org.blaze.platform.taskbar

import org.blaze.platform.api.OsType
import org.blaze.platform.api.PlatformIdentity
import org.blaze.platform.api.detectOsType

/**
 * Picks the taskbar-progress backend for the host platform (same detection style as
 * [org.blaze.platform.autostart.AutoStartServiceFactory]).
 *
 * Linux desktops (KDE Plasma in particular) render progress from the
 * `com.canonical.Unity.LauncherEntry` D-Bus protocol, which AWT does not implement,
 * so they use [LinuxTaskbarProgressService]; macOS and Windows drive the JDK's own
 * [AwtTaskbarProgressService]; anything else falls back to a no-op.
 */
object TaskbarProgressServiceFactory {

    fun create(
        identity: PlatformIdentity,
        osName: String = System.getProperty("os.name"),
    ): TaskbarProgressService = when (detectOsType(osName)) {
        OsType.LINUX -> LinuxTaskbarProgressService(identity)
        OsType.WINDOWS, OsType.MACOS -> AwtTaskbarProgressService()
        OsType.UNKNOWN -> UnsupportedTaskbarProgressService()
    }
}
