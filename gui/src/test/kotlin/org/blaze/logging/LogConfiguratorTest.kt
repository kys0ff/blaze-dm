package org.blaze.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.settings.LogLevel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [LogConfigurator] against the real logback context configured by the app's
 * logback.xml, so XML typos and appender-name mismatches are caught too. Waits with real
 * delays because the configurator observes the settings flow on the IO dispatcher.
 */
class LogConfiguratorTest {

    private val tempDir = Files.createTempDirectory("blaze-log-config-test")
    private val scope = CoroutineScope(Job())
    private var savedFileAppender: Appender<ILoggingEvent>? = null

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempDir.toFile().deleteRecursively()
        // The tests mutate the shared logback context; restore it so other tests
        // running in the same JVM see the default configuration.
        val root = rootLogger()
        root.level = Level.INFO
        if (root.getAppender("FILE") == null) {
            savedFileAppender?.let { root.addAppender(it) }
        }
    }

    private fun rootLogger() =
        (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME)

    @Test
    fun `settings changes are applied to the logback context in real time`() {
        runBlocking {
            val repository = EngineSettingsRepository(tempDir)
            LogConfigurator(repository, tempDir).start(scope)
            delay(500) // let the collector pick up the initial value

            val root = rootLogger()
            // Default settings: INFO, file logging on (FILE appender attached from logback.xml).
            assertEquals(Level.INFO, root.level)
            assertNotNull(
                root.getAppender("FILE"),
                "logback.xml must declare a FILE appender attached to root"
            )
            savedFileAppender = root.getAppender("FILE")

            repository.updateSettings { it.copy(logLevel = LogLevel.DEBUG, fileLoggingEnabled = false) }
            delay(500)
            assertEquals(Level.DEBUG, root.level)
            assertNull(root.getAppender("FILE"), "FILE appender should be detached when file logging is off")

            repository.updateSettings { it.copy(logLevel = LogLevel.OFF, fileLoggingEnabled = true) }
            delay(500)
            assertEquals(Level.OFF, root.level)
            // Regression guard: a detached appender must be re-attachable (unlike a stopped
            // FileAppender, which closes its output stream permanently).
            assertNotNull(root.getAppender("FILE"), "FILE appender should be re-attached when re-enabled")
        }
    }

    @Test
    fun `log directory is created on start`() {
        runBlocking {
            val repository = EngineSettingsRepository(tempDir)
            val configurator = LogConfigurator(repository, tempDir)
            configurator.start(scope)
            delay(500)
            assertTrue(Files.isDirectory(configurator.logDir))
        }
    }
}
