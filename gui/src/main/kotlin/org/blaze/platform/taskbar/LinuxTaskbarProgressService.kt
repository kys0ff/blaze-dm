package org.blaze.platform.taskbar

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory

/**
 * [TaskbarProgressService] for Linux, publishing download progress through the
 * `com.canonical.Unity.LauncherEntry` D-Bus protocol - the mechanism KDE Plasma's
 * task manager uses to draw a progress bar on a running app's icon, exactly how
 * Firefox/Chromium show their download progress there.
 *
 * Plasma does not query an exported object: it listens session-wide for the
 * `LauncherEntry.Update` signal and reads the values from its `a{sv}` payload, so we
 * only ever *send* that signal (see [UnityLauncherEntry]) - no object is exported.
 *
 * The connection is deliberately **private** (`withShared(false)`): dbus-java caches
 * one reference-counted session connection per address by default, and a second
 * holder would keep it open past [org.blaze.tray.api.TrayService.dispose], so the
 * tray's reinstall would hit "Object already exported". A private connection keeps
 * this feature fully isolated from the tray's D-Bus lifecycle.
 *
 * Every entry point degrades quietly: without a session bus [isSupported] is false
 * and all updates are dropped, leaving the app running with no taskbar bar.
 */
class LinuxTaskbarProgressService(
    /**
     * Launcher identity Plasma resolves to a `.desktop` file (strip `application://`).
     * Progress only appears once a desktop entry of that storage id is installed and
     * its `StartupWMClass` matches the app window - [LauncherEntryInstaller] guarantees
     * both, since jpackage's package tooling does not install one by itself.
     */
    private val launcherUri: String = DEFAULT_LAUNCHER_URI,
    private val launcherEntryInstaller: LauncherEntryInstaller = LauncherEntryInstaller(),
) : TaskbarProgressService {

    private val logger = LoggerFactory.getLogger(LinuxTaskbarProgressService::class.java)

    private val lock = Any()
    private var connection: DBusConnection? = null
    private var supportedCache: Boolean? = null
    private var desktopEntryEnsured = false

    override val isSupported: Boolean
        get() = synchronized(lock) {
            supportedCache ?: runCatching { openConnection(); true }
                .getOrDefault(false)
                .also {
                    supportedCache = it
                    if (!it) closeConnection()
                }
        }

    override fun setProgress(progress: Float) {
        if (!isSupported) return
        val fraction = progress.coerceIn(0f, 1f).toDouble()
        // Both keys: `progress-visible` arms the bar, `progress` (0..1) fills it.
        sendUpdate(
            buildMap {
                put("progress-visible", Variant(true, "b"))
                put("progress", Variant(fraction, "d"))
            },
        )
    }

    override fun clear() {
        if (!isSupported) return
        sendUpdate(mapOf("progress-visible" to Variant(false, "b"), "progress" to Variant(0.0, "d")))
    }

    override fun dispose() {
        synchronized(lock) { closeConnection() }
    }

    private fun openConnection(): DBusConnection = synchronized(lock) {
        connection ?: DBusConnectionBuilder.forSessionBus().withShared(false).build()
            .also {
                connection = it
                // Plasma drops Update signals whose launcher URI has no desktop entry;
                // make sure ours exists before the first broadcast goes out.
                if (!desktopEntryEnsured) {
                    desktopEntryEnsured = true
                    runCatching { launcherEntryInstaller.ensureInstalled() }
                        .onFailure { logger.warn("Failed to install the LauncherEntry desktop entry", it) }
                }
            }
    }

    private fun closeConnection() {
        val conn = connection ?: return
        runCatching { conn.disconnect() }
            .onFailure { logger.warn("Failed to close the LauncherEntry D-Bus connection", it) }
        connection = null
        supportedCache = null
    }

    /** Broadcasts the LauncherEntry `Update` signal; Plasma binds to it via a match rule. */
    private fun sendUpdate(properties: Map<String, Variant<*>>) {
        val conn = synchronized(lock) { connection } ?: return
        runCatching {
            conn.sendMessage(UnityLauncherEntry.Update(OBJECT_PATH, launcherUri, properties))
        }.onFailure { logger.warn("Failed to emit the LauncherEntry Update signal", it) }
    }

    private companion object {
        // Signal source path; never exported, only needs to be a valid object path.
        const val OBJECT_PATH = "/org/blaze/Launcher"
        const val DEFAULT_LAUNCHER_URI = "application://" + LauncherEntryInstaller.DESKTOP_FILE_NAME
    }
}
