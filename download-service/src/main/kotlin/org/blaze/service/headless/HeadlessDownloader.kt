package org.blaze.service.headless

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.blaze.download.api.ServicePaths
import org.blaze.service.DownloaderServiceHost
import org.blaze.service.config.LocalApiSettingsRepository
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch

/**
 * Headless downloader: runs the engine + loopback API with no desktop UI.
 *
 * This is the process the CLI and a future browser bridge talk to when the GUI is not open, and the
 * background service mode for servers/kiosks. It takes the single-instance lock, binds the loopback API,
 * publishes its descriptor, then parks until the JVM is asked to exit (Ctrl-C / SIGTERM), shutting the
 * engine down cleanly so progress is persisted.
 *
 * Usage: `blaze-serve [--storage-dir PATH] [--port N] [--no-auth] [--allow-origin ORIGIN]`
 */
private val logger = LoggerFactory.getLogger("org.blaze.service.headless")

fun main(args: Array<String>) {
    val options = parseArgs(args)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepo = LocalApiSettingsRepository(options.storageDir)
    val settings = settingsRepo.load().let { base ->
        base.copy(
            port = options.port ?: base.port,
            requireAuth = if (options.noAuth) false else base.requireAuth,
            allowedOrigins = options.origins.ifEmpty { base.allowedOrigins },
        )
    }

    if (options.noAuth) {
        logger.warn("Authentication is DISABLED for the local API - only do this on a trusted single-user machine.")
    }

    val host = try {
        DownloaderServiceHost.create(
            storageDir = options.storageDir,
            scope = scope,
            apiSettings = settings,
            startServer = true,
        )
    } catch (e: Exception) {
        logger.error("Could not start the downloader service (is another instance already running?)", e)
        return
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down the headless downloader...")
        host.close()
    })

    logger.info("Blaze headless downloader serving on http://{}:{}", host.boundHost, host.boundPort)
    logger.info("Bearer token (owner-only): {}", ServicePaths.tokenFileFor(options.storageDir))

    // Park the main thread until the shutdown hook runs.
    CountDownLatch(1).await()
}

private data class Options(
    val storageDir: Path,
    val port: Int?,
    val noAuth: Boolean,
    val origins: List<String>,
)

private fun parseArgs(args: Array<String>): Options {
    val defaultDir = Paths.get(System.getProperty("user.home"), ServicePaths.DEFAULT_STORAGE_DIR_NAME)
    var storageDir = defaultDir
    var port: Int? = null
    var noAuth = false
    val origins = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--storage-dir", "-d" -> { storageDir = Paths.get(args[++i]) }
            "--port", "-p" -> { port = args[++i].toIntOrNull() }
            "--allow-origin" -> { origins += args[++i] }
            "--no-auth" -> { noAuth = true }
            else -> logger.warn("Ignoring unknown argument {}", a)
        }
        i++
    }
    return Options(storageDir, port, noAuth, origins)
}
