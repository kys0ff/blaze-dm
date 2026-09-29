package org.blaze.service

import kotlinx.coroutines.CoroutineScope
import org.blaze.download.api.ApiVersion
import org.blaze.download.api.AuthMode
import org.blaze.download.api.DownloadApiException
import org.blaze.download.api.DownloadsApi
import org.blaze.download.api.ServiceDescriptor
import org.blaze.download.api.ServicePaths
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.service.DownloaderServiceHost.Companion.create
import org.blaze.service.auth.ApiTokenStore
import org.blaze.service.config.LocalApiSettings
import org.blaze.service.instance.ServiceDiscovery
import org.blaze.service.instance.ServiceLock
import org.blaze.service.transport.LocalApiServer
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Owns the running downloader: the engine, the application [DownloadsApi] on top of it, and - optionally -
 * the loopback HTTP transport external clients connect to.
 *
 * It is the single composition root for the non-GUI world (headless daemon and tests), so the same wiring
 * the GUI performs lives in exactly one place. [create] uses [ServiceLock] so only one process drives a
 * given data directory; everyone else talks to it through the discovered HTTP endpoint instead of
 * spinning up a competing engine.
 */
class DownloaderServiceHost(
    val api: DownloadsApi,
    private val engine: DownloadEngine,
    private val server: LocalApiServer?,
    private val lock: ServiceLock?,
    private val discovery: ServiceDiscovery?,
    private val tokenFile: Path?,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(DownloaderServiceHost::class.java)

    // TODO: make use of isServing
    val isServing: Boolean get() = server != null
    val boundHost: String? get() = server?.boundHost
    val boundPort: Int get() = server?.boundPort ?: -1

    /** Publishes the endpoint descriptor once the server is actually bound. */
    fun publish() {
        val srv = server ?: return
        val token = tokenFile ?: return
        discovery?.publish(
            ServiceDescriptor(
                host = srv.boundHost,
                port = srv.boundPort,
                apiVersion = ApiVersion.CURRENT,
                pid = ProcessHandle.current().pid(),
                startedAtEpochMs = System.currentTimeMillis(),
                tokenFile = token.toAbsolutePath().toString(),
            )
        )
    }

    override fun close() {
        server?.stop()
        discovery?.clear()
        lock?.close()
        runCatching { kotlinx.coroutines.runBlocking { engine.shutdown() } }
        logger.info("Downloader service stopped.")
    }

    companion object {
        /**
         * Builds a host around [storageDir]. When [startServer] is true it attempts to take the service
         * lock and bind the HTTP transport; if another process already owns the directory it returns a host
         * with no server (a client-only view) so the caller can connect to the incumbent instead.
         */
        fun create(
            storageDir: Path,
            scope: CoroutineScope,
            apiSettings: LocalApiSettings,
            startServer: Boolean,
        ): DownloaderServiceHost {
            val repository = DownloadRepository(storageDir)
            val settingsRepository = EngineSettingsRepository(storageDir)
            val engine: DownloadEngine = DownloadManager(
                scope = scope,
                repository = repository,
                settingsRepository = settingsRepository,
            )
            val tokenFile = ServicePaths.tokenFileFor(storageDir)
            val tokenStore = ApiTokenStore(tokenFile)
            val authMode = if (apiSettings.requireAuth) AuthMode.BEARER_TOKEN else AuthMode.NONE
            val api = ApplicationDownloadService(
                engine = engine,
                scope = scope,
                settingsRepository = settingsRepository,
                authMode = authMode,
                eventMinIntervalMillis = apiSettings.eventMinIntervalMillis,
            )

            if (!startServer || !apiSettings.enabled) {
                return DownloaderServiceHost(api, engine, null, null, null, null)
            }

            val lock = ServiceLock(ServicePaths.lockFileFor(storageDir))
            if (!lock.acquire()) {
                // Another live process owns this data directory; do not double-bind.
                throw DownloadApiException(
                    org.blaze.download.api.ApiErrorCode.CONFLICT,
                    "another downloader service already owns $storageDir",
                )
            }
            val server = LocalApiServer(api, apiSettings, tokenStore, scope)
            server.start()
            val host = DownloaderServiceHost(api, engine, server, lock, ServiceDiscovery(storageDir), tokenFile)
            host.publish()
            return host
        }
    }
}
