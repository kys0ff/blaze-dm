package org.blaze.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.LogLevel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bridges app settings to the runtime logback configuration: the root log level and the
 * on/off state of the rolling `FILE` appender declared in `logback.xml`.
 *
 * [start] observes the persisted settings flow, so changes saved from the Settings screen
 * take effect immediately without a restart; the current value is also applied once at
 * startup. Everything degrades quietly when slf4j is bound to a non-logback backend
 * (e.g. slf4j-simple in unit tests).
 */
class LogConfigurator(
    private val settingsRepository: EngineSettingsRepository,
    storageDir: Path
) {
    private val logger = LoggerFactory.getLogger(LogConfigurator::class.java)

    /** Directory the rolling log files live in; mirrors the `LOG_DIR` property in logback.xml. */
    val logDir: Path = storageDir.resolve("logs")

    private val context: LoggerContext? =
        LoggerFactory.getILoggerFactory() as? LoggerContext

    // Kept aside so the appender survives being detached from the root logger and can be
    // re-attached later; a stopped FileAppender can never be restarted, only recreated.
    private var fileAppender: Appender<ILoggingEvent>? = null

    fun start(scope: CoroutineScope) {
        runCatching { Files.createDirectories(logDir) }
            .onFailure { logger.error("Failed to create log directory {}", logDir, it) }

        scope.launch(Dispatchers.IO) {
            settingsRepository.settings.collect { settings ->
                runCatching { apply(settings) }
                    .onFailure { logger.error("Failed to apply logging settings", it) }
            }
        }
    }

    private fun apply(settings: DownloadSettings) {
        val root = context?.getLogger(Logger.ROOT_LOGGER_NAME) ?: return

        root.level = settings.logLevel.toLogbackLevel()

        val appender = fileAppender
            ?: root.getAppender(FILE_APPENDER_NAME)?.also { fileAppender = it }
        if (appender != null) {
            // Attach/detach instead of start/stop: stopping a FileAppender closes its
            // output stream permanently, while a detached appender can be re-attached.
            val attached = root.getAppender(FILE_APPENDER_NAME) != null
            if (settings.fileLoggingEnabled && !attached) {
                root.addAppender(appender)
            } else if (!settings.fileLoggingEnabled && attached) {
                root.detachAppender(appender)
            }
        }

        logger.debug(
            "Logging configured: level={}, fileLogging={} (dir={})",
            settings.logLevel, settings.fileLoggingEnabled, logDir
        )
    }

    private fun LogLevel.toLogbackLevel(): Level = when (this) {
        LogLevel.OFF -> Level.OFF
        LogLevel.ERROR -> Level.ERROR
        LogLevel.WARN -> Level.WARN
        LogLevel.INFO -> Level.INFO
        LogLevel.DEBUG -> Level.DEBUG
        LogLevel.TRACE -> Level.TRACE
    }

    private companion object {
        const val FILE_APPENDER_NAME = "FILE"
    }
}
