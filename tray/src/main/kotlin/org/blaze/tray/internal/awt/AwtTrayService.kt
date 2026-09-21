package org.blaze.tray.internal.awt

import org.blaze.tray.api.TrayConfig
import org.blaze.tray.api.TrayMenuItem
import org.blaze.tray.api.TrayService
import org.slf4j.LoggerFactory
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * [TrayService] backed by `java.awt.SystemTray` — works on Windows and macOS, and on
 * Linux desktops whose tray/indicator protocol AWT supports. Everywhere else (e.g.
 * GNOME without an AppIndicator extension) every entry point degrades quietly instead
 * of crashing the app. This is the only class allowed to touch tray AWT APIs.
 */
internal class AwtTrayService : TrayService {

    private val logger = LoggerFactory.getLogger(AwtTrayService::class.java)

    private var trayIcon: TrayIcon? = null
    private var toggleItem: MenuItem? = null
    private var currentToggle: TrayMenuItem.WindowToggle? = null
    private var windowVisible = true

    override val isSupported: Boolean
        get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    override fun install(config: TrayConfig) {
        dispose()
        if (!isSupported) {
            logger.info("System tray is not supported on this desktop; skipping tray install")
            return
        }
        runCatching {
            val tray = SystemTray.getSystemTray()

            var toggle: MenuItem? = null
            var toggleAction: (() -> Unit)? = null
            var toggleModel: TrayMenuItem.WindowToggle? = null
            val menu = PopupMenu()
            for (item in config.menu) {
                when (item) {
                    TrayMenuItem.Separator -> menu.addSeparator()
                    is TrayMenuItem.Item -> menu.add(MenuItem(item.label).apply {
                        isEnabled = item.enabled
                        addActionListener { item.onClick() }
                    })
                    is TrayMenuItem.WindowToggle -> menu.add(MenuItem(item.labelFor(windowVisible)).apply {
                        addActionListener { item.onClick() }
                        toggle = this
                        toggleAction = item.onClick
                        toggleModel = item
                    })
                }
            }

            val iconSize = runCatching { tray.trayIconSize.width }.getOrDefault(16).coerceIn(16, 32)
            val icon = TrayIcon(config.icon.create(iconSize), config.title, menu).apply {
                // Getter/setter pair is asymmetric (isImageAutoSize/setImageAutoSize), so
                // there is no synthesized property - call the setter directly.
                setImageAutoSize(true)
                // Double-click runs the window toggle, the classic tray-app shortcut.
                toggleAction?.let { action ->
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (e.clickCount % 2 == 0) action()
                        }
                    })
                }
            }
            tray.add(icon)

            trayIcon = icon
            toggleItem = toggle
            currentToggle = toggleModel
        }.onFailure {
            logger.warn("Failed to install the tray icon; continuing without one", it)
            trayIcon = null
            toggleItem = null
            currentToggle = null
        }
    }

    override fun setWindowVisible(visible: Boolean) {
        windowVisible = visible
        val item = toggleItem ?: return
        val model = currentToggle ?: return
        item.label = model.labelFor(visible)
    }

    override fun dispose() {
        val icon = trayIcon ?: return
        runCatching { SystemTray.getSystemTray().remove(icon) }
            .onFailure { logger.warn("Failed to remove the tray icon", it) }
        trayIcon = null
        toggleItem = null
        currentToggle = null
    }
}
