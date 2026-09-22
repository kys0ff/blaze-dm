package org.blaze.platform.autostart

import org.blaze.platform.api.OsType
import org.blaze.platform.api.PlatformIdentity
import org.blaze.platform.api.detectOsType

/** Picks the [AutoStartService] matching the host OS for [identity]. */
object AutoStartServiceFactory {

    fun create(
        identity: PlatformIdentity,
        osName: String = System.getProperty("os.name"),
    ): AutoStartService = when (detectOsType(osName)) {
        OsType.WINDOWS -> WindowsAutoStartService(identity)
        OsType.MACOS -> MacOsAutoStartService(identity)
        OsType.LINUX -> LinuxAutoStartService(identity)
        OsType.UNKNOWN -> UnsupportedAutoStartService()
    }
}
