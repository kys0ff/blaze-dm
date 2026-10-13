package org.blaze.data

import androidx.compose.ui.input.key.Key
import org.blaze.presentation.components.BlazeKeymap
import org.island.ui.keymap.IslandKeyBinding
import kotlin.test.*

/**
 * [KeymapPersistence] is the single bridge between the persisted chord ([StoredKeyBinding]), the live
 * [BlazeKeymap] the app acts on, and the Settings draft. These tests walk that bridge without a display:
 * a binding survives a store round-trip, pushing a persisted map re-points the live keymap (and ignores
 * unknown action names so a hand-edited file can never crash), and the draft-resolution / conflict helpers
 * the Settings screen leans on behave for override, default, unbind and duplicate chords.
 *
 * Fixtures are built through [KeymapPersistence.fromBinding] rather than hand-writing a key code, so a
 * stored chord carries the same key *location* a real recorded press would — the recorder is exercised
 * through the same conversion the running app uses.
 *
 * The event-shaped halves ([BlazeKeymap.capture], [BlazeKeymap.matchAction]) read a native key event whose
 * only public constructor is internal to Compose, so they are covered by the UI, not here; the resolution
 * they depend on — [BlazeKeymap.effectiveBinding] — is what these cases exercise through the same [Key].
 */
class KeymapPersistenceTest {

    @AfterTest
    fun resetKeymap() {
        BlazeKeymap.replaceOverrides(emptyMap(), emptySet())
    }

    /** The stored form of a real chord, so its key location matches what a capture would record. */
    private fun stored(key: Key, ctrl: Boolean = true, shift: Boolean = false, alt: Boolean = false) =
        KeymapPersistence.fromBinding(IslandKeyBinding(key, ctrl, shift, alt))

    @Test
    fun `a binding survives a store round-trip`() {
        val binding = IslandKeyBinding(Key.N, ctrl = true, shift = true)

        val restored = KeymapPersistence.toBinding(KeymapPersistence.fromBinding(binding))

        assertEquals(binding, restored)
    }

    @Test
    fun `applying a persisted override re-points the live keymap`() {
        val chord = stored(Key.J)
        KeymapPersistence.applyTo(bindings = mapOf(BlazeKeymap.Action.SelectAll.name to chord), unbound = emptyList())

        assertEquals(IslandKeyBinding(Key.J), BlazeKeymap.effectiveBinding(BlazeKeymap.Action.SelectAll))
    }

    @Test
    fun `applying an unbind clears the live binding`() {
        KeymapPersistence.applyTo(bindings = emptyMap(), unbound = listOf(BlazeKeymap.Action.NewDownload.name))

        assertNull(BlazeKeymap.effectiveBinding(BlazeKeymap.Action.NewDownload))
    }

    @Test
    fun `unknown action names in the file are ignored`() {
        KeymapPersistence.applyTo(
            bindings = mapOf("NoSuchAction" to stored(Key.J)),
            unbound = listOf("AlsoMissing"),
        )

        // Nothing was applied, so the untouched default still resolves and no crash happened.
        assertEquals(BlazeKeymap.Action.NewDownload.defaultBinding, BlazeKeymap.effectiveBinding(BlazeKeymap.Action.NewDownload))
    }

    @Test
    fun `draft resolution prefers the override, then the default, then null when unbound`() {
        val bindings = mapOf(BlazeKeymap.Action.SelectAll.name to stored(Key.B))
        val unbound = listOf(BlazeKeymap.Action.Remove.name)

        assertEquals(
            IslandKeyBinding(Key.B),
            KeymapPersistence.draftBinding(BlazeKeymap.Action.SelectAll, bindings, unbound)
        )
        assertEquals(
            BlazeKeymap.Action.TogglePause.defaultBinding,
            KeymapPersistence.draftBinding(BlazeKeymap.Action.TogglePause, bindings, unbound),
        )
        assertNull(KeymapPersistence.draftBinding(BlazeKeymap.Action.Remove, bindings, unbound))
    }

    @Test
    fun `two actions sharing a chord are reported as a conflict naming the other`() {
        val shared = stored(Key.J)
        val bindings = mapOf(
            BlazeKeymap.Action.SelectAll.name to shared,
            BlazeKeymap.Action.NewDownload.name to shared,
        )

        val conflicts = KeymapPersistence.findConflicts(bindings, unbound = emptyList())

        assertEquals(BlazeKeymap.Action.NewDownload.name, conflicts[BlazeKeymap.Action.SelectAll.name])
        assertEquals(BlazeKeymap.Action.SelectAll.name, conflicts[BlazeKeymap.Action.NewDownload.name])
    }

    @Test
    fun `distinct chords produce no conflict`() {
        val bindings = mapOf(
            BlazeKeymap.Action.SelectAll.name to stored(Key.J),
            BlazeKeymap.Action.NewDownload.name to stored(Key.Q),
        )

        assertTrue(KeymapPersistence.findConflicts(bindings, unbound = emptyList()).isEmpty())
    }

    @Test
    fun `an unbound action is never a conflict`() {
        val shared = stored(Key.J)
        val bindings = mapOf(
            BlazeKeymap.Action.SelectAll.name to shared,
            BlazeKeymap.Action.NewDownload.name to shared,
        )

        // Unbinding SelectAll removes it from the comparison, leaving NewDownload alone.
        val conflicts = KeymapPersistence.findConflicts(bindings, unbound = listOf(BlazeKeymap.Action.SelectAll.name))

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `actionOf returns null for an unknown name and the action for a known one`() {
        assertNull(KeymapPersistence.actionOf("NoSuchAction"))
        assertEquals(BlazeKeymap.Action.SelectAll, KeymapPersistence.actionOf(BlazeKeymap.Action.SelectAll.name))
    }
}
