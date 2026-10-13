package org.blaze.data

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.nativeKeyLocation
import org.blaze.presentation.components.BlazeKeymap
import org.island.ui.keymap.IslandKeyBinding

/**
 * The one bridge between the persisted keymap ([AppSettings.keyBindings] / [AppSettings.unboundShortcuts]),
 * the live [BlazeKeymap] the app acts on, and the [IslandKeyBinding] a chord recorder captures.
 *
 * It exists so none of the three concerns leaks into the others: [AppSettings] stays pure primitives, the
 * presentation keymap stays free of persistence, and the Settings draft is resolved through the exact same
 * [IslandKeyBinding] shape the running app uses — so a draft chord a user records is guaranteed to mean the same key
 * once applied.
 */
object KeymapPersistence {

    /** Resolve a stored chord into a live binding. Unknown / non-letter keys round-trip through the raw code. */
    fun toBinding(stored: StoredKeyBinding): IslandKeyBinding =
        IslandKeyBinding(
            key = Key(stored.keyCode, stored.keyLocation),
            ctrl = stored.ctrl,
            shift = stored.shift,
            alt = stored.alt,
        )

    /** Write a binding back to disk form, capturing the physical key code + location so it round-trips. */
    fun fromBinding(binding: IslandKeyBinding): StoredKeyBinding =
        StoredKeyBinding(
            keyCode = binding.key.nativeKeyCode,
            keyLocation = binding.key.nativeKeyLocation,
            ctrl = binding.ctrl,
            shift = binding.shift,
            alt = binding.alt,
        )

    /** The chord a recorder stores for a captured key press, or null when the press is not a bindable chord. */
    fun capture(event: KeyEvent): StoredKeyBinding? =
        BlazeKeymap.capture(event)?.let(::fromBinding)

    /**
     * Push the persisted keymap into the live [BlazeKeymap], replacing whatever overrides are in effect.
     * Unrecognized action names (a hand-edited or newer/older file) are ignored so a bad key can never
     * crash startup, mirroring the language-code fallback.
     */
    fun applyTo(bindings: Map<String, StoredKeyBinding>, unbound: List<String>) {
        val overrides = bindings.mapNotNull { (name, stored) ->
            actionOf(name)?.let { it to toBinding(stored) }
        }.toMap()
        val cleared = unbound.mapNotNull(::actionOf).toSet()
        BlazeKeymap.replaceOverrides(overrides, cleared)
    }

    /** The action named [name], or null when it is not a known bindable action. */
    fun actionOf(name: String): BlazeKeymap.Action? =
        BlazeKeymap.bindableActions.firstOrNull { it.name == name }

    // ── Settings-draft resolution ──────────────────────────────────────────────
    // The Settings screen edits a draft, so it resolves chords from the draft's own maps rather than the
    // live keymap (which still reflects the last applied / persisted state until Apply).

    /** The draft binding in force for [action]: the draft override, else null if unbound, else the default. */
    fun draftBinding(
        action: BlazeKeymap.Action,
        bindings: Map<String, StoredKeyBinding>,
        unbound: List<String>,
    ): IslandKeyBinding? = when {
        action == BlazeKeymap.Action.None || action.name in unbound -> null
        else -> bindings[action.name]?.let(::toBinding) ?: action.defaultBinding
    }

    /** The formatted chord a draft row prints, or null when the action is unbound (empty column). */
    fun draftDisplay(
        action: BlazeKeymap.Action,
        bindings: Map<String, StoredKeyBinding>,
        unbound: List<String>,
    ): String? = BlazeKeymap.format(draftBinding(action, bindings, unbound))

    /**
     * Duplicate detection across the draft: maps an action's name to the name of another action that would
     * answer the same chord. Only real, acceptable chords are compared, so an empty column or a rejected
     * bare key never produces a spurious conflict. Two bindings collide when their key + modifier set match.
     */
    fun findConflicts(
        bindings: Map<String, StoredKeyBinding>,
        unbound: List<String>,
    ): Map<String, String> {
        val chords = BlazeKeymap.bindableActions.mapNotNull { action ->
            draftBinding(action, bindings, unbound)?.let { action.name to it.signature }
        }
        val conflicts = LinkedHashMap<String, String>()
        for ((nameA, sigA) in chords) {
            val clash = chords.firstOrNull { (nameB, sigB) -> nameB != nameA && sigB == sigA }
            if (clash != null) conflicts[nameA] = clash.first
        }
        return conflicts
    }
}
