package org.blaze.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

/**
 * A logback appender that turns every [ILoggingEvent] into a [LogEntry] and hands it to [sink]; it owns
 * no storage itself so the buffer and its lifecycle stay in [ServerLogRepository]. It is attached to the
 * root logger programmatically (never declared in `logback.xml`), which is what lets the same app run
 * unchanged under a non-logback backend — tests bound to slf4j-simple simply never reach this class.
 */
class MemoryLogAppender(
    private val sink: (LogEntry) -> Unit,
) : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        val fullLogger = event.loggerName
        sink(
            LogEntry(
                timestampEpochMs = event.timeStamp,
                level = event.level.levelStr,
                // The IDE's Log window reads the trailing class name, not the whole package path; a
                // deep `org.blaze.engine.core.DownloadManager` would otherwise crowd out the message.
                logger = fullLogger.substringAfterLast('.'),
                message = event.formattedMessage,
            ),
        )
    }
}
