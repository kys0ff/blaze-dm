package org.island.ui.keymap

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.island.foundation.CurrentIslandPlatform
import org.island.foundation.IslandPlatform

/**
 * The reusable keymap model: which physical key, under which modifiers, an action is bound to;
 * how a live [KeyEvent] is matched against such a binding; and how the chord reads on this OS.
 *
 * An app supplies its own action type [A] plus the default binding per action; everything else —
 * user overrides held in snapshot state (so menus, tooltips and search results recompose the moment
 * a rebind lands), exact modifier matching, chord capture for a recorder, and the per-platform
 * label formatting (Ctrl/Alt/Shift spelled out on Windows and Linux, the ⌘/⇧/⌥ glyphs with no
 * separator on macOS) — lives here, once.
 */

/**
 * A binding: one physical [key] under the modifier set. `ctrl` means the OS's primary modifier:
 * Ctrl on Windows and Linux, Command on macOS - matching how the handlers already read it.
 */
data class IslandKeyBinding(
    val key: Key,
    val ctrl: Boolean = true,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /** A stable signature for conflict detection: two bindings with the same signature are the same chord. */
    val signature: String get() = "${key.keyCode}:$ctrl:$shift:$alt"
}

/**
 * Whether [event] is a press of this binding. The modifier set has to match exactly, so Ctrl+Alt+F
 * does not read as the app's Ctrl+F, and a bare key does not fire a chord that needs modifiers.
 */
fun IslandKeyBinding.matches(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key != key) return false
    val primary = event.isCtrlPressed || event.isMetaPressed
    return !(primary != ctrl || event.isShiftPressed != shift || event.isAltPressed != alt)
}

/**
 * The [IslandKeyBinding] a recorder would store for [event], or null when the press is not a
 * bindable chord (a modifier-only press, or a key-up). Reads the raw key and modifiers, so the
 * recorder and the matcher agree on exactly the same chord shape.
 */
fun captureIslandBinding(event: KeyEvent): IslandKeyBinding? {
    if (event.type != KeyEventType.KeyDown) return null
    val pressed = event.key
    if (pressed in MODIFIER_KEYS) return null
    val primary = event.isCtrlPressed || event.isMetaPressed
    return IslandKeyBinding(pressed, ctrl = primary, shift = event.isShiftPressed, alt = event.isAltPressed)
}

/**
 * Whether a binding is safe to accept: it carries the OS primary modifier, or it is a bare key that
 * the chrome already listens for without modifiers (Delete/Enter/Space and the standalone keys). This
 * keeps a rebind from stealing ordinary text entry (a bare "A" would fire on every keystroke).
 */
fun isAcceptableIslandBinding(binding: IslandKeyBinding): Boolean =
    binding.ctrl || binding.alt || binding.key in STANDALONE_KEYS

/** Formats a single [binding] for this OS - "Ctrl+A", "⌘A", "Del" - or null for no binding. */
fun formatIslandChord(binding: IslandKeyBinding?): String? = binding?.let { b ->
    val modifiers = if (CurrentIslandPlatform == IslandPlatform.MacOS) {
        buildString {
            if (b.alt) append("⌥")
            if (b.shift) append("⇧")
            if (b.ctrl) append("⌘")
        }
    } else {
        buildString {
            if (b.alt) append("Alt+")
            if (b.shift) append("Shift+")
            if (b.ctrl) append("Ctrl+")
        }
    }
    modifiers + islandKeyName(b.key)
}

/**
 * A keymap over an app's own action type [A]: the default bindings come from [defaultBindingOf],
 * the bindable set from [bindableActions], and user state (overrides plus explicitly unbound
 * actions) is held in snapshot state so every reader recomposes on a rebind.
 */
class IslandKeymap<A : Any>(
    val bindableActions: List<A>,
    private val defaultBindingOf: (A) -> IslandKeyBinding?,
) {
    private val overrideBindings = mutableStateOf<Map<A, IslandKeyBinding>>(emptyMap())
    private val clearedActions = mutableStateOf<Set<A>>(emptySet())

    /** The binding actually in force for [action]: the user override, else the default, else none. */
    fun effectiveBinding(action: A): IslandKeyBinding? = when {
        action in clearedActions.value -> null
        else -> overrideBindings.value[action] ?: defaultBindingOf(action)
    }

    /** Whether the user has explicitly unbound [action] (as opposed to it simply keeping its default). */
    fun isCleared(action: A): Boolean = action in clearedActions.value

    /** Whether [action] currently runs on a user override rather than its built-in default. */
    fun hasOverride(action: A): Boolean = overrideBindings.value.containsKey(action)

    /** Replace the whole live override set - the shape the persisted keymap and the settings draft reduce to. */
    fun replaceOverrides(overrides: Map<A, IslandKeyBinding>, cleared: Set<A>) {
        overrideBindings.value = overrides
        clearedActions.value = cleared
    }

    /** The action [event] triggers, or null when no accelerator claims it. */
    fun matchAction(event: KeyEvent): A? =
        bindableActions.firstOrNull { effectiveBinding(it)?.matches(event) == true }

    /** The live label for [action], or null when it has no effective binding. */
    fun displayValue(action: A): String? = formatIslandChord(effectiveBinding(action))

    /** Formats a draft binding the same way a live label reads, so preview and reality agree. */
    fun format(binding: IslandKeyBinding?): String? = formatIslandChord(binding)
}

/** Modifier keys whose press alone is not a chord; a recorder waits past these. */
private val MODIFIER_KEYS = setOf(
    Key.ShiftLeft, Key.ShiftRight,
    Key.CtrlLeft, Key.CtrlRight,
    Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight,
    Key.CapsLock,
)

/** Keys that may be bound without a modifier because they never produce text in ordinary fields. */
private val STANDALONE_KEYS = setOf(
    Key.Delete, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.Escape,
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown,
    Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
)

/** The display name of a key inside a chord, per OS; an unlisted key prints nothing. */
private fun islandKeyName(key: Key): String = when (key) {
    Key.A -> "A"
    Key.B -> "B"
    Key.C -> "C"
    Key.D -> "D"
    Key.E -> "E"
    Key.F -> "F"
    Key.G -> "G"
    Key.H -> "H"
    Key.I -> "I"
    Key.J -> "J"
    Key.K -> "K"
    Key.L -> "L"
    Key.M -> "M"
    Key.N -> "N"
    Key.O -> "O"
    Key.P -> "P"
    Key.Q -> "Q"
    Key.R -> "R"
    Key.S -> "S"
    Key.T -> "T"
    Key.U -> "U"
    Key.V -> "V"
    Key.W -> "W"
    Key.X -> "X"
    Key.Y -> "Y"
    Key.Z -> "Z"
    Key.Zero -> "0"
    Key.One -> "1"
    Key.Two -> "2"
    Key.Three -> "3"
    Key.Four -> "4"
    Key.Five -> "5"
    Key.Six -> "6"
    Key.Seven -> "7"
    Key.Eight -> "8"
    Key.Nine -> "9"
    Key.Spacebar -> "Space"
    Key.Enter -> if (CurrentIslandPlatform == IslandPlatform.MacOS) "↵" else "Enter"
    Key.Delete -> if (CurrentIslandPlatform == IslandPlatform.MacOS) "⌫" else "Del"
    Key.Escape -> "Esc"
    Key.MoveHome -> "Home"
    Key.MoveEnd -> "End"
    Key.PageUp -> "PgUp"
    Key.PageDown -> "PgDn"
    Key.DirectionUp -> "↑"
    Key.DirectionDown -> "↓"
    Key.DirectionLeft -> "←"
    Key.DirectionRight -> "→"
    Key.F1 -> "F1"
    Key.F2 -> "F2"
    Key.F3 -> "F3"
    Key.F4 -> "F4"
    Key.F5 -> "F5"
    Key.F6 -> "F6"
    Key.F7 -> "F7"
    Key.F8 -> "F8"
    Key.F9 -> "F9"
    Key.F10 -> "F10"
    Key.F11 -> "F11"
    Key.F12 -> "F12"
    // An unlisted key prints its modifiers alone - the signal to extend this map.
    else -> ""
}
