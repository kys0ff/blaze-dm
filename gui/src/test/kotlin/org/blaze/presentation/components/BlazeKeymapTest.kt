package org.blaze.presentation.components

import androidx.compose.ui.input.key.Key
import org.island.ui.keymap.IslandKeyBinding
import kotlin.test.*

/**
 * The override machinery [BlazeKeymap] gained for re-bindable shortcuts. What every read (matching, the
 * printed accelerator, the Settings preview) resolves through is [BlazeKeymap.effectiveBinding], so these
 * cases pin the resolution contract the whole keymap rests on: default when untouched, override when
 * rebound, null when unbound — with an explicit unbind always winning over an override.
 *
 * Key-event matching itself is exercised end-to-end in the gui module (where a real [androidx.compose.ui
 * .input.key.KeyEvent] can be built); here the pure state resolution is verified without a display.
 */
class BlazeKeymapTest {

    @AfterTest
    fun resetKeymap() {
        BlazeKeymap.replaceOverrides(emptyMap(), emptySet())
    }

    @Test
    fun `an untouched action resolves to its built-in default`() {
        assertEquals(
            BlazeKeymap.Action.SelectAll.defaultBinding,
            BlazeKeymap.effectiveBinding(BlazeKeymap.Action.SelectAll)
        )
        assertNull(BlazeKeymap.effectiveBinding(BlazeKeymap.Action.None))
    }

    @Test
    fun `an override replaces the default`() {
        val custom = IslandKeyBinding(Key.J)
        BlazeKeymap.replaceOverrides(mapOf(BlazeKeymap.Action.SelectAll to custom), emptySet())

        assertEquals(custom, BlazeKeymap.effectiveBinding(BlazeKeymap.Action.SelectAll))
        assertTrue(BlazeKeymap.hasOverride(BlazeKeymap.Action.SelectAll))
    }

    @Test
    fun `an explicitly cleared action resolves to null`() {
        BlazeKeymap.replaceOverrides(emptyMap(), setOf(BlazeKeymap.Action.OpenSettings))

        assertNull(BlazeKeymap.effectiveBinding(BlazeKeymap.Action.OpenSettings))
        assertTrue(BlazeKeymap.isCleared(BlazeKeymap.Action.OpenSettings))
    }

    @Test
    fun `a clear wins over an override on the same action`() {
        BlazeKeymap.replaceOverrides(
            mapOf(BlazeKeymap.Action.SelectAll to IslandKeyBinding(Key.J)),
            setOf(BlazeKeymap.Action.SelectAll),
        )

        assertNull(BlazeKeymap.effectiveBinding(BlazeKeymap.Action.SelectAll))
    }

    @Test
    fun `the printed accelerator reflects the override and an unbound action prints nothing`() {
        assertNull(BlazeKeymap.displayValue(BlazeKeymap.Action.None))

        BlazeKeymap.replaceOverrides(
            mapOf(BlazeKeymap.Action.ToggleDetails to IslandKeyBinding(Key.Delete, ctrl = false)),
            emptySet(),
        )
        val label = BlazeKeymap.displayValue(BlazeKeymap.Action.ToggleDetails)
        assertNotNull(label)
        // The default ToggleDetails is Ctrl+I; the override is a bare Delete, spelled "Del"/"⌦".
        assertTrue(label.contains("Del") || label.contains("⌦"), "unexpected label: $label")
    }

    @Test
    fun `only real actions are offered for binding`() {
        assertFalse(BlazeKeymap.Action.None in BlazeKeymap.bindableActions)
        assertTrue(BlazeKeymap.Action.SelectAll in BlazeKeymap.bindableActions)
        assertEquals(BlazeKeymap.Action.entries.size - 1, BlazeKeymap.bindableActions.size)
    }

    @Test
    fun `a chord is acceptable only with a modifier or a non-typing key`() {
        // Ctrl (primary) present by default, or Alt, or a key that never types text: fine.
        assertTrue(BlazeKeymap.isAcceptable(IslandKeyBinding(Key.A)))
        assertTrue(BlazeKeymap.isAcceptable(IslandKeyBinding(Key.A, ctrl = false, alt = true)))
        assertTrue(BlazeKeymap.isAcceptable(IslandKeyBinding(Key.Delete, ctrl = false)))
        // A bare letter would fire on every keystroke: rejected.
        assertFalse(BlazeKeymap.isAcceptable(IslandKeyBinding(Key.A, ctrl = false)))
    }

    @Test
    fun `bindings with the same chord share a signature and different ones do not`() {
        val a = IslandKeyBinding(Key.A)
        val b = IslandKeyBinding(Key.A)
        val c = IslandKeyBinding(Key.B)
        val shifted = IslandKeyBinding(Key.A, shift = true)

        assertEquals(a.signature, b.signature)
        assertNotEquals(a.signature, c.signature)
        assertNotEquals(a.signature, shifted.signature)
    }
}
