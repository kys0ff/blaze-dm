package org.blaze.platform.taskbar

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

    fun create(osName: String = System.getProperty("os.name")): TaskbarProgressService {
        val name = osName.lowercase()
        return when {
            name.contains("linux") -> LinuxTaskbarProgressService()
            name.contains("win") || name.contains("mac") || name.contains("darwin") ->
                AwtTaskbarProgressService()
            else -> UnsupportedTaskbarProgressService()
        }
    }
}
