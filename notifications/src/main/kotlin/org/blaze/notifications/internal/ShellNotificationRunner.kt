package org.blaze.notifications.internal

import java.util.concurrent.TimeUnit
import org.slf4j.Logger

/**
 * Runs a spawned CLI helper (osascript, powershell, ...) to completion off the caller's
 * thread and reports success. Notification transports are fire-and-forget: stdout/stderr
 * are merged into the caller's error stream and only the exit code matters.
 *
 * [timeout] bounds pathological hangs - a wedged helper must never stall the app, and a
 * dropped notification is an acceptable outcome. Failures are logged by the caller's
 * [logger] (kept quiet here so each backend can word its own message).
 */
internal fun runCli(
    logger: Logger,
    failureMessage: String,
    command: List<String>,
    timeout: TimeUnit = TimeUnit.SECONDS,
    timeoutAmount: Long = 15,
): Boolean {
    return runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutAmount, timeout)) {
            process.destroyForcibly()
            logger.warn("$failureMessage: helper timed out after $timeoutAmount $timeout")
            return false
        }
        if (process.exitValue() != 0) {
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            logger.warn("$failureMessage: exited ${process.exitValue()}${if (output.isEmpty()) "" else " - $output"}")
            false
        } else {
            true
        }
    }.getOrElse {
        logger.warn(failureMessage, it)
        false
    }
}
