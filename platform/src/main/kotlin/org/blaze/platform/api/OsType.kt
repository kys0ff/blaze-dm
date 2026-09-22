package org.blaze.platform.api

/** Coarse desktop OS classification the service factories dispatch on. */
enum class OsType { WINDOWS, MACOS, LINUX, UNKNOWN }

/**
 * Shared `os.name` sniffing used by every service factory in this module (the same
 * detection style the app-shell services already used), so per-OS fallback choices
 * stay consistent across autostart, taskbar and file-integration backends.
 */
fun detectOsType(osName: String = System.getProperty("os.name")): OsType {
    val name = osName.lowercase()
    return when {
        name.contains("win") -> OsType.WINDOWS
        name.contains("mac") || name.contains("darwin") -> OsType.MACOS
        name.contains("linux") -> OsType.LINUX
        else -> OsType.UNKNOWN
    }
}
