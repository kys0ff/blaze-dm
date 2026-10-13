package org.blaze.presentation.screens.settings.state

import androidx.compose.ui.input.key.Key
import org.blaze.data.AppSettings
import org.blaze.data.KeymapPersistence
import org.blaze.data.StoredKeyBinding
import org.blaze.presentation.components.BlazeKeymap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.island.ui.keymap.*

/**
 * The Settings draft's keybinding validation, derived (not stored) from the buffered
 * [AppSettings.keyBindings] / [AppSettings.unboundShortcuts]. These cases pin that a duplicate chord and a
 * text-hijacking bare key both surface on the right action and block Apply/OK through
 * [SettingsState.hasValidationError], while a clean or reverted draft does not — and that a committed
 * draft carrying a conflict is still "not dirty" yet blocked.
 *
 * Fixtures go through [KeymapPersistence.fromBinding] so a stored chord carries the same key location a
 * real recording would, keeping the resolution identical to production.
 */
class SettingsKeyBindingValidationTest {

    private fun stored(key: Key, ctrl: Boolean = true) =
        KeymapPersistence.fromBinding(IslandKeyBinding(key, ctrl = ctrl))

    private fun stateWith(bindings: Map<String, StoredKeyBinding>, unbound: List<String> = emptyList()) =
        SettingsState(appSettings = AppSettings(keyBindings = bindings, unboundShortcuts = unbound))

    @Test
    fun `defaults carry no keybinding errors`() {
        val state = SettingsState()

        assertTrue(state.keyBindingConflicts.isEmpty())
        assertTrue(state.unsafeKeyBindings.isEmpty())
        assertFalse(state.hasValidationError)
    }

    @Test
    fun `a duplicate chord flags both actions and blocks Apply`() {
        val shared = stored(Key.J)
        val app = AppSettings(keyBindings = mapOf("SelectAll" to shared, "NewDownload" to shared))
        val state = SettingsState(appSettings = app, savedAppSettings = app)

        assertEquals("NewDownload", state.keyBindingConflicts["SelectAll"])
        assertEquals("SelectAll", state.keyBindingConflicts["NewDownload"])
        assertTrue(state.hasValidationError)
        // Committed draft == saved, so it is not "dirty", yet the conflict still blocks.
        assertFalse(state.isDirty)
    }

    @Test
    fun `a bare typing key with no modifier is unsafe`() {
        val state = stateWith(mapOf("SelectAll" to stored(Key.A, ctrl = false)))

        assertTrue("SelectAll" in state.unsafeKeyBindings)
        assertTrue(state.hasValidationError)
    }

    @Test
    fun `a standalone non-typing key is accepted without a modifier`() {
        val state = stateWith(mapOf("Remove" to stored(Key.Delete, ctrl = false)))

        assertTrue(state.unsafeKeyBindings.isEmpty())
    }

    @Test
    fun `rebinding one action onto its own default changes nothing`() {
        // SelectAll's default is Ctrl+A; explicitly storing the same chord is not a conflict.
        val state = stateWith(mapOf("SelectAll" to stored(Key.A)))

        assertTrue(state.keyBindingConflicts.isEmpty())
        assertTrue(state.unsafeKeyBindings.isEmpty())
    }
}
