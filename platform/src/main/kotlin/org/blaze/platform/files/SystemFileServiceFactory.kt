package org.blaze.platform.files

/** Picks the [SystemFileService] matching the host OS (same detection style as AutoStartServiceFactory). */
object SystemFileServiceFactory {

    fun create(osName: String = System.getProperty("os.name")): SystemFileService {
        val name = osName.lowercase()
        return when {
            name.contains("win") -> WindowsSystemFileService()
            name.contains("mac") || name.contains("darwin") -> MacOsSystemFileService()
            name.contains("linux") -> LinuxSystemFileService()
            else -> UnsupportedSystemFileService()
        }
    }
}
