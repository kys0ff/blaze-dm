package org.blaze.logging

import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The in-memory log stream the Server Logs tool window renders — a fixed-capacity ring buffer that
 * carries the running process's log output (including the local API server's own lines, which share the
 * logback root) so a user can watch and search it live without opening a file.
 *
 * It attaches a [MemoryLogAppender] to the logback root at construction, degrading quietly when slf4j
 * is bound to a non-logback backend (mirrors [LogConfigurator]'s defensive cast) so unit tests never
 * install an appender. [record] is public so callers — and tests — can push a line without the backend.
 */
class ServerLogRepository(autoAttach: Boolean = true) {
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    /** The buffered lines, oldest first; the panel draws and searches this list top-to-bottom. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _autoScroll = MutableStateFlow(true)
    /** Whether the panel sticks to the newest line; turned off when the user scrolls up to read back. */
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    init {
        if (autoAttach) attachSafely()
    }

    /** Appends [entry], evicting the oldest once the buffer is full, and republishes the snapshot. */
    fun record(entry: LogEntry) {
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > CAPACITY) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    /** Empties the buffer (the panel's "Clear"); the appender keeps running so new lines still land. */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    fun setAutoScroll(enabled: Boolean) {
        _autoScroll.update { enabled }
    }

    private fun attachSafely() {
        runCatching {
            val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
            val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
            val created = MemoryLogAppender(::record).apply {
                name = APPENDER_NAME
                setContext(context)
                start()
            }
            root.addAppender(created)
        }
    }

    private companion object {
        const val CAPACITY = 2000
        const val APPENDER_NAME = "BLAZE_MEMORY"
    }
}
