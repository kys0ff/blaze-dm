package org.blaze.platform.tray

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
class AwtTrayService : TrayService {

    private val logger = LoggerFactory.getLogger(AwtTrayService::class.java)

    private var trayIcon: TrayIcon? = null
    private var toggleItem: MenuItem? = null
    private var currentLabels: TrayLabels? = null

    override val isSupported: Boolean
        get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    override fun install(labels: TrayLabels, actions: TrayActions) {
        dispose()
        if (!isSupported) {
            logger.info("System tray is not supported on this desktop; skipping tray install")
            return
        }
        runCatching {
            val tray = SystemTray.getSystemTray()

            val toggle = MenuItem(labels.hide)
            toggle.addActionListener { actions.onToggleWindow() }
            val pauseAll = MenuItem(labels.pauseAll)
            pauseAll.addActionListener { actions.onPauseAll() }
            val resumeAll = MenuItem(labels.resumeAll)
            resumeAll.addActionListener { actions.onResumeAll() }
            val quit = MenuItem(labels.quit)
            quit.addActionListener { actions.onQuit() }

            val menu = PopupMenu().apply {
                add(toggle)
                add(pauseAll)
                add(resumeAll)
                addSeparator()
                add(quit)
            }

            val iconSize = runCatching { tray.trayIconSize.width }.getOrDefault(16).coerceIn(16, 32)
            val icon = TrayIcon(BlazeTrayIcon.create(iconSize), "Blaze", menu).apply {
                // Getter/setter pair is asymmetric (isImageAutoSize/setImageAutoSize), so
                // there is no synthesized property - call the setter directly.
                setImageAutoSize(true)
                // Double-click toggles the window, the classic tray-app shortcut.
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount % 2 == 0) actions.onToggleWindow()
                    }
                })
            }
            tray.add(icon)

            trayIcon = icon
            toggleItem = toggle
            currentLabels = labels
        }.onFailure {
            logger.warn("Failed to install the tray icon; continuing without one", it)
            trayIcon = null
            toggleItem = null
            currentLabels = null
        }
    }

    override fun setWindowVisible(visible: Boolean) {
        val item = toggleItem ?: return
        val labels = currentLabels ?: return
        item.label = if (visible) labels.hide else labels.show
    }

    override fun dispose() {
        val icon = trayIcon ?: return
        runCatching { SystemTray.getSystemTray().remove(icon) }
            .onFailure { logger.warn("Failed to remove the tray icon", it) }
        trayIcon = null
        toggleItem = null
        currentLabels = null
    }
}
