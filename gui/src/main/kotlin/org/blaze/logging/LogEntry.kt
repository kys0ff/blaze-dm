package org.blaze.logging

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One buffered log line, held in memory for the [ServerLogRepository] and rendered in the Server Logs
 * tool window. Kept as plain fields (level/logger are the strings the appender read, not logback types)
 * so the presentation layer never has to depend on the logging backend.
 */
data class LogEntry(
    val timestampEpochMs: Long,
    val level: String,
    val logger: String,
    val message: String,
) {
    /**
     * The whole line as the panel draws and searches it: `12:34:56.789 INFO  engine - message`.
     * The find bar highlights over [render]ed text, so this is the single source for both display and
     * match indices.
     */
    fun render(): String =
        "${TIME_FORMATTER.format(Instant.ofEpochMilli(timestampEpochMs).atZone(ZoneId.systemDefault()))} " +
            "${level.padEnd(5)} ${logger} - $message"

    private companion object {
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
