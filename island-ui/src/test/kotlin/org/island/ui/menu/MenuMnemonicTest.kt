package org.island.ui.menu

import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mnemonic underline [IslandMenu] draws for access keys (the underlined "C" of a system menu's
 * "Close"). The rule is deliberately forgiving: only a valid index decorates a character, and
 * nothing about a missing or out-of-range one may change the drawn text.
 */
class MenuMnemonicTest {

    @Test
    fun `a null index draws the label plain`() {
        val text = labelWithMnemonic("Restore", null)
        assertEquals("Restore", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }

    @Test
    fun `the mnemonic index underlines exactly one character`() {
        val text = labelWithMnemonic("Close", 0)
        assertEquals("Close", text.text)
        assertEquals(1, text.spanStyles.size)
        val span = text.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(1, span.end)
        assertEquals(TextDecoration.Underline, span.item.textDecoration)
    }

    @Test
    fun `an out-of-range index degrades to the plain label`() {
        for (index in listOf(-1, 5, Int.MAX_VALUE)) {
            val text = labelWithMnemonic("Close", index)
            assertEquals("Close", text.text)
            assertTrue(text.spanStyles.isEmpty(), "index $index must not decorate anything")
        }
    }

    @Test
    fun `menu actions carry no mnemonic unless one is declared`() {
        assertNull(MenuEntry.Action(label = "Close").mnemonic)
        assertEquals(0, MenuEntry.Action(label = "Close", mnemonic = 0).mnemonic)
    }
}
