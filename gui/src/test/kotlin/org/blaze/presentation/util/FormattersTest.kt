package org.blaze.presentation.util

import org.blaze.i18n.EnStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The size / speed formatters. The headline regression guarded here is the status-bar "MB/s/s" bug:
 * a rate must carry exactly one per-second suffix. [formatSpeed] is the single place that appends it,
 * so callers passing its result through another wrapper would double it — these tests pin the output.
 */
class FormattersTest {

    @Test
    fun `a rate gets exactly one per-second suffix`() {
        val megabytePerSec = formatSpeed(1_048_576L, EnStrings)

        assertEquals("1.0 MB/s", megabytePerSec)
        assertFalse(megabytePerSec.endsWith("/s/s"), "rate must not double-append the /s suffix")
    }

    @Test
    fun `kibibyte and byte rates are formatted`() {
        assertEquals("1.0 KB/s", formatSpeed(1_024L, EnStrings))
        assertEquals("512 B/s", formatSpeed(512L, EnStrings))
    }

    @Test
    fun `zero and unknown sizes stay human`() {
        assertEquals("0 B", formatSize(0L, EnStrings))
        assertEquals(EnStrings.common.unknown, formatSize(null, EnStrings))
    }
}
