package org.blaze.service

import kotlinx.coroutines.CoroutineScope
import org.blaze.download.api.ApiVersion
import org.blaze.download.api.DownloadsApi
import org.blaze.download.api.ServiceDescriptor
import org.blaze.download.api.ServicePaths
import org.blaze.service.auth.ApiTokenStore
import org.blaze.service.config.LocalApiSettings
import org.blaze.service.instance.ServiceDiscovery
import org.blaze.service.instance.ServiceLock
import org.blaze.service.security.ClientConsentGate
import org.blaze.service.transport.LocalApiServer
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Best-effort helper for a host that already owns an engine + [DownloadsApi] (the GUI) to additionally
 * expose the loopback HTTP API, so a CLI or browser bridge can drive the same running downloads.
 *
 * It takes the [ServiceLock] first: only one process may own a data directory's API endpoint, so a second
 * GUI/daemon simply won't publish a duplicate. [tryStart] returns false (and leaves the caller's UI fully
 * functional) when the API is disabled or the directory is already owned.
 */
class LocalApiLauncher(
    private val storageDir: Path,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(LocalApiLauncher::class.java)
    private val discovery = ServiceDiscovery(storageDir)
    private var server: LocalApiServer? = null
    private var lock: ServiceLock? = null

    val isRunning: Boolean get() = server != null
    val port: Int get() = server?.boundPort ?: -1

    /**
     * Exposes the API if the directory is free. [consentGate] is the GUI's chance to make the user
     * acknowledge every new third-party client; a host with no UI leaves it as
     * [ClientConsentGate.ALLOW_ALL] and nobody is asked.
     */
    fun tryStart(
        api: DownloadsApi,
        settings: LocalApiSettings,
        consentGate: ClientConsentGate = ClientConsentGate.ALLOW_ALL,
    ): Boolean {
        if (!settings.enabled) return false
        val candidate = ServiceLock(ServicePaths.lockFileFor(storageDir))
        if (!candidate.acquire()) {
            logger.info("Another downloader service already owns {}; not starting a second local API.", storageDir)
            return false
        }
        val tokenStore = ApiTokenStore(ServicePaths.tokenFileFor(storageDir))
        val srv = LocalApiServer(api, settings, tokenStore, scope, consentGate)
        return try {
            srv.start()
            discovery.publish(
                ServiceDescriptor(
                    host = srv.boundHost,
                    port = srv.boundPort,
                    apiVersion = ApiVersion.CURRENT,
                    pid = ProcessHandle.current().pid(),
                    startedAtEpochMs = System.currentTimeMillis(),
                    tokenFile = ServicePaths.tokenFileFor(storageDir).toAbsolutePath().toString(),
                )
            )
            server = srv
            lock = candidate
            logger.info("Local download API listening on http://{}", srv.let { "${it.boundHost}:${it.boundPort}" })
            true
        } catch (e: Exception) {
            logger.error("Failed to start the local download API", e)
            candidate.close()
            false
        }
    }

    fun stop() {
        server?.stop()
        server = null
        discovery.clear()
        lock?.close()
        lock = null
    }
}
