package org.blaze.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.blaze.download.api.DownloadsApi
import org.blaze.download.api.ServicePaths
import org.blaze.service.LocalApiLauncher
import org.blaze.service.config.LocalApiSettingsRepository
import org.blaze.service.security.ClientConsentGate
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The GUI's live view onto the embedded local download API server: whether it is up, the endpoint it is
 * bound to, and where its bearer token lives. Read by the Server Logs tool window and the hamburger's
 * server submenu so the Start/Stop/Restart affordances reflect reality rather than a guess.
 */
data class ServerStatus(
    val running: Boolean,
    val boundHost: String?,
    val boundPort: Int,
    val tokenFile: Path,
) {
    /** The `host:port` the CLI or a browser bridge would dial, or null while the server is down. */
    val endpoint: String? get() = boundHost?.takeIf { running && boundPort > 0 }?.let { "$it:$boundPort" }
}

/**
 * Owns the single [LocalApiLauncher] the process runs, so the GUI (not just [org.blaze.Main]) can start,
 * stop and restart the local API server and read its endpoint.
 *
 * Startup stays best-effort exactly as the inline block in `Main` was: a second instance declines on the
 * [org.blaze.service.instance.ServiceLock] and [start] simply reports not-running, and no failure here is
 * allowed to throw at the caller — the window must open regardless of whether the API publishes.
 */
class DownloadServiceController(
    private val launcher: LocalApiLauncher,
    private val api: DownloadsApi,
    storageDir: Path,
    private val consentGate: ClientConsentGate,
) {
    private val logger = LoggerFactory.getLogger(DownloadServiceController::class.java)
    private val settingsRepository = LocalApiSettingsRepository(storageDir)
    private val tokenFile = ServicePaths.tokenFileFor(storageDir)

    private val _status = MutableStateFlow(ServerStatus(running = false, boundHost = null, boundPort = -1, tokenFile = tokenFile))
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    /** Attempts to publish the loopback API; returns whether it is now running. */
    fun start(): Boolean {
        val settings = settingsRepository.load()
        val started = runCatching { launcher.tryStart(api, settings, consentGate) }
            .onFailure { logger.warn("Local download API did not start; the GUI is unaffected.", it) }
            .getOrDefault(false)
        refresh(if (started) settings.host else null)
        return _status.value.running
    }

    fun stop() {
        runCatching { launcher.stop() }
            .onFailure { logger.warn("The local download API did not stop cleanly.", it) }
        refresh(null)
    }

    /** Stops and re-publishes, re-reading the on-disk settings so a changed port/host takes effect. */
    fun restart() {
        stop()
        start()
    }

    private fun refresh(boundHost: String?) {
        _status.value = ServerStatus(
            running = launcher.isRunning,
            boundHost = boundHost,
            boundPort = launcher.port,
            tokenFile = tokenFile,
        )
    }
}
