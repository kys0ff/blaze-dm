package org.blaze.tray

import org.blaze.tray.api.TrayService
import org.blaze.tray.internal.awt.AwtTrayService
import org.blaze.tray.internal.sni.SniTrayService

/**
 * Picks the tray backend for the host platform. Linux desktops (KDE Plasma in
 * particular) implement StatusNotifierItem over D-Bus and only render AWT's
 * legacy XEmbed icons without relaying clicks, so SNI is preferred there;
 * Windows and macOS keep using [AwtTrayService].
 */
object TrayServiceFactory {

    fun create(osName: String = System.getProperty("os.name")): TrayService {
        val name = osName.lowercase()
        return when {
            name.contains("linux") -> SniTrayService()
            else -> AwtTrayService()
        }
    }
}
