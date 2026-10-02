package org.blaze.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the ring buffer the Server Logs window renders: append order (oldest first), eviction once the
 * 2000-entry cap is crossed, [ServerLogRepository.clear] emptying it, and [LogEntry.render] producing the
 * line the panel draws and searches. The repository is built with `autoAttach = false` so no appender is
 * installed on the test backend; [ServerLogRepository.record] pushes lines directly.
 */
class ServerLogRepositoryTest {

    private fun entry(message: String, level: String = "INFO") =
        LogEntry(timestampEpochMs = 1_700_000_000_000L, level = level, logger = "org.blaze.Test", message = message)

    @Test
    fun `entries are published oldest first`() {
        val repository = ServerLogRepository(autoAttach = false)

        repository.record(entry("first"))
        repository.record(entry("second"))

        assertEquals(listOf("first", "second"), repository.entries.value.map { it.message })
    }

    @Test
    fun `recording past capacity evicts the oldest lines`() {
        val repository = ServerLogRepository(autoAttach = false)

        repeat(2005) { index -> repository.record(entry("line-$index")) }

        val snapshot = repository.entries.value
        assertEquals(2000, snapshot.size)
        // The five oldest were dropped; the newest is still the tail.
        assertEquals("line-5", snapshot.first().message)
        assertEquals("line-2004", snapshot.last().message)
    }

    @Test
    fun `clear empties the buffer but keeps recording`() {
        val repository = ServerLogRepository(autoAttach = false)
        repository.record(entry("a"))
        repository.clear()
        assertTrue(repository.entries.value.isEmpty())

        repository.record(entry("b"))
        assertEquals(listOf("b"), repository.entries.value.map { it.message })
    }

    @Test
    fun `render lays out time, padded level, logger and message`() {
        val rendered = entry("hello world", level = "WARN").render()

        assertTrue(rendered.contains("WARN "), "level is padded to five columns")
        assertTrue(rendered.contains("Test - hello world"), "logger tail and message follow the ' - ' separator")
    }
}
