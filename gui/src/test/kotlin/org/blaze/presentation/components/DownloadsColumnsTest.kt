package org.blaze.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The table's column model: order, the hide/show set the header's Table options menu edits, and the
 * one column that can never be hidden. Both the header and every row render the list this returns, so
 * these rules decide what the whole table looks like.
 */
class DownloadsColumnsTest {

    @Test
    fun `nothing hidden keeps every column in table order`() {
        assertEquals(DownloadsColumn.entries.toList(), downloadsColumnsFor(emptySet()))
    }

    @Test
    fun `hidden ids drop their columns, and the rest keep their order`() {
        val visible = downloadsColumnsFor(setOf(DownloadsColumn.Eta.id, DownloadsColumn.Speed.id))

        assertEquals(
            listOf(
                DownloadsColumn.Name,
                DownloadsColumn.Progress,
                DownloadsColumn.Size,
                DownloadsColumn.State,
            ),
            visible,
        )
    }

    @Test
    fun `the name column can never be hidden`() {
        // It is how a row is identified and clicked; a table without it is unusable.
        val visible = downloadsColumnsFor(DownloadsColumn.entries.map { it.id }.toSet())

        assertEquals(listOf(DownloadsColumn.Name), visible)
        assertTrue(DownloadsColumn.Name.id in visible.map { it.id })
        assertFalse(DownloadsColumn.Size.id in visible.map { it.id })
    }

    @Test
    fun `an unknown hidden id changes nothing`() {
        assertEquals(DownloadsColumn.entries.size, downloadsColumnsFor(setOf("no-such-column")).size)
    }
}
