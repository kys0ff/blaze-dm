package org.blaze.platform.autostart

/** Picks the [AutoStartService] matching the host OS (same detection style as SystemOpen). */
object AutoStartServiceFactory {

    fun create(osName: String = System.getProperty("os.name")): AutoStartService {
        val name = osName.lowercase()
        return when {
            name.contains("win") -> WindowsAutoStartService()
            name.contains("mac") || name.contains("darwin") -> MacOsAutoStartService()
            name.contains("linux") -> LinuxAutoStartService()
            else -> UnsupportedAutoStartService()
        }
    }
}
