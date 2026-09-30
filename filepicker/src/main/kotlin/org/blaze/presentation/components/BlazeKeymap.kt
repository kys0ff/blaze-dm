package org.blaze.presentation.components

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
import org.blaze.presentation.components.BlazeKeymap.displayValue
import org.blaze.presentation.components.BlazeKeymap.matches

/**
 * The app's one keymap: what an action is bound to, how that binding is recognized, and how it reads
 * on this OS.
 *
 * Before this existed the accelerator text was assembled per call site (`shortcutOf("A")` here, a
 * literal "Del" there) while the key that was actually listened for was spelled out again inside the
 * screen's key handler. Two copies of the same fact, nothing keeping them honest: a menu could print
 * a chord no code ever handled — Settings was exactly that, labeled everywhere and bound nowhere.
 *
 * Here a binding is a *fact about the keyboard* held once, and both ends read it: [matches] is what a
 * key handler asks, [displayValue] is what a menu row prints. A label can now only appear next to an
 * action if the same object answers the keypress.
 *
 * The built-in [Action.defaultBinding] is only the starting point. Users may rebind any action from
 * Settings, so every read goes through [effectiveBinding] — the live override if one exists, otherwise
 * the default. The overrides are held in snapshot state, so a rebind updates the handlers *and* every
 * menu row, tooltip and search result that prints the chord, without a restart.
 *
 * macOS shows the command glyph with no separator, Windows and Linux spell the modifiers out — the
 * same split the dialog footers use, off the single [CurrentUiPlatform] switch.
 */
object BlazeKeymap {

    /** Every action that can carry an accelerator somewhere in the chrome. */
    enum class Action(val defaultBinding: Binding?) {
        NewDownload(Binding(Key.N)),
        SearchEverywhere(Binding(Key.K)),
        FocusListSearch(Binding(Key.F)),
        ToggleDetails(Binding(Key.I)),
        SelectAll(Binding(Key.A)),

        // The row handler only fires this with a selection present; the keymap records the key, the
        // screen keeps its own precondition.
        TogglePause(Binding(Key.Spacebar)),
        Remove(Binding(Key.Delete, ctrl = false)),

        // Bare Enter opens the one selected row, so the menu's "Open File" can show it.
        OpenSelected(Binding(Key.Enter, ctrl = false)),

        // Bound by the shell only when Settings can be reached, and spelled out because it does not
        // fit the single-modifier pattern the other bindings use.
        OpenSettings(Binding(Key.S, alt = true)),

        /** Menu rows with no real binding. They show no accelerator rather than an imaginary one. */
        None(null);

        /** Whether this action is offered in the Shortcuts settings page (everything but the null row). */
        val bindable: Boolean get() = this != None
    }

    /** The bindable actions, in the order the Shortcuts settings page lists them. */
    val bindableActions: List<Action> = Action.entries.filter { it.bindable }

    /** Which physical key, under which modifiers. `ctrl` means the OS's primary modifier: Ctrl on
     * Windows and Linux, Command on macOS — matching how the handlers already read it. */
    data class Binding(
        val key: Key,
        val ctrl: Boolean = true,
        val shift: Boolean = false,
        val alt: Boolean = false,
    ) {
        /** A stable signature for conflict detection: two bindings with the same signature are the same chord. */
        val signature: String get() = "${key.keyCode}:$ctrl:$shift:$alt"
    }

    // ── live overrides ─────────────────────────────────────────────────────────
    // Held as snapshot state so any composable that reads a chord (directly through [displayValue] or
    // through [platformShortcutLabels]) recomposes when a binding changes, while the plain matchers
    // still read the same values at event time.

    private val overrideBindings = mutableStateOf<Map<Action, Binding>>(emptyMap())
    private val clearedActions = mutableStateOf<Set<Action>>(emptySet())

    /** The binding that is actually in force for [action]: the user override, else the default.
     * `None` and explicitly unbound actions resolve to null, which prints no accelerator and matches no key. */
    fun effectiveBinding(action: Action): Binding? = when {
        action == Action.None || action in clearedActions.value -> null
        else -> overrideBindings.value[action] ?: action.defaultBinding
    }

    /** Whether the user has explicitly unbound [action] (as opposed to it simply keeping its default). */
    fun isCleared(action: Action): Boolean = action in clearedActions.value

    /** Whether [action] currently runs on a user override rather than its built-in default. */
    fun hasOverride(action: Action): Boolean = overrideBindings.value.containsKey(action)

    /** Replace the whole live override set — the shape the persisted keymap and the settings draft reduce to. */
    fun replaceOverrides(overrides: Map<Action, Binding>, cleared: Set<Action>) {
        overrideBindings.value = overrides
        clearedActions.value = cleared
    }

    /** Re-read the live overrides from a source of truth (used by the app at startup and on settings change). */
    fun applyPersisted(overrides: Map<Action, Binding>, cleared: Set<Action>) =
        replaceOverrides(overrides, cleared)

    // ── matching ───────────────────────────────────────────────────────────────

    /** Whether [event] is a press of this binding. The modifier set has to match exactly, so Ctrl+Alt+F
     * does not read as the app's Ctrl+F, and a bare key does not fire a chord that needs modifiers. */
    fun Binding.matches(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key != key) return false
        val primary = event.isCtrlPressed || event.isMetaPressed
        return !(primary != ctrl || event.isShiftPressed != shift || event.isAltPressed != alt)
    }

    /** The action [event] triggers, or null when no accelerator claims it — the whole job of a shell
     * key handler that wants to stay in sync with what the menus print. */
    fun matchAction(event: KeyEvent): Action? =
        bindableActions.firstOrNull { effectiveBinding(it)?.matches(event) == true }

    /**
     * The [Binding] a recorder would store for [event], or null when the press is not a bindable chord
     * (a modifier-only press, or a key-up). Reads the raw key and modifiers, so the recorder and the
     * matcher agree on exactly the same chord shape.
     */
    fun capture(event: KeyEvent): Binding? {
        if (event.type != KeyEventType.KeyDown) return null
        val pressed = event.key
        if (pressed in MODIFIER_KEYS) return null
        val primary = event.isCtrlPressed || event.isMetaPressed
        return Binding(pressed, ctrl = primary, shift = event.isShiftPressed, alt = event.isAltPressed)
    }

    // ── display ────────────────────────────────────────────────────────────────

    /**
     * The live label for [action] — "Ctrl+A", "⌘A", "Del" — or null when the action has no effective
     * binding, which is how a menu row leaves the accelerator column empty instead of inventing one.
     */
    fun displayValue(action: Action): String? = format(effectiveBinding(action))

    /** Formats a single [binding] (or null) the same way a live label reads; the settings preview uses this
     * so the draft chord and the applied chord never spell differently. */
    fun format(binding: Binding?): String? = binding?.let { formatBinding(it) }

    private fun formatBinding(binding: Binding): String {
        val modifiers = if (CurrentUiPlatform == UiPlatform.MacOS) {
            buildString {
                if (binding.alt) append("⌥")
                if (binding.shift) append("⇧")
                if (binding.ctrl) append("⌘")
            }
        } else {
            buildString {
                if (binding.alt) append("Alt+")
                if (binding.shift) append("Shift+")
                if (binding.ctrl) append("Ctrl+")
            }
        }
        return modifiers + keyName(binding.key)
    }

    /** Whether a binding is safe to accept: it carries the OS primary modifier, or it is a bare key that
     * the chrome already listens for without modifiers (Delete/Enter/Space and the standalone keys). This
     * keeps a rebind from stealing ordinary text entry (a bare "A" would fire on every keystroke). */
    fun isAcceptable(binding: Binding): Boolean =
        binding.ctrl || binding.alt || binding.key in STANDALONE_KEYS

    private fun keyName(key: Key): String = when (key) {
        Key.N -> "N"
        Key.K -> "K"
        Key.F -> "F"
        Key.I -> "I"
        Key.A -> "A"
        Key.S -> "S"
        Key.Spacebar -> "Space"
        Key.Enter -> if (CurrentUiPlatform == UiPlatform.MacOS) "↩" else "Enter"
        Key.Delete -> if (CurrentUiPlatform == UiPlatform.MacOS) "⌦" else "Del"
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
        // Every binding the app declares is listed above; an unlisted key would print its modifiers
        // alone, which is the signal to extend this map rather than to guess a name here.
        else -> ""
    }

    /** Modifier keys whose press alone is not a chord; a recorder waits past these. */
    private val MODIFIER_KEYS = setOf(
        Key.ShiftLeft, Key.ShiftRight,
        Key.CtrlLeft, Key.CtrlRight,
        Key.AltLeft, Key.AltRight,
        Key.MetaLeft, Key.MetaRight,
        Key.CapsLock,
    )

    /** Keys that may be bound without a modifier because they never produce text in the app's fields. */
    private val STANDALONE_KEYS = setOf(
        Key.Delete, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.Escape,
        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
        Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown,
        Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
    )
}

/**
 * Builds the bundle of shortcut strings the chrome reads from [LocalShortcutLabels], from the one
 * keymap rather than from per-screen literals. It reads live bindings through [BlazeKeymap.displayValue],
 * so calling it inside a composition subscribes that composition to rebind changes.
 */
fun platformShortcutLabels(): ShortcutLabels = ShortcutLabels(
    newDownload = displayValue(BlazeKeymap.Action.NewDownload).orEmpty(),
    search = displayValue(BlazeKeymap.Action.FocusListSearch).orEmpty(),
    // The IDE's Search Everywhere is Cmd/Ctrl+N in IntelliJ (where that is "New Project"); here N is
    // taken by New Download, so the popup keeps the IDE's second, undisputed binding.
    quickSearch = displayValue(BlazeKeymap.Action.SearchEverywhere).orEmpty(),
    selectAll = displayValue(BlazeKeymap.Action.SelectAll).orEmpty(),
    settings = displayValue(BlazeKeymap.Action.OpenSettings).orEmpty(),
    togglePause = displayValue(BlazeKeymap.Action.TogglePause).orEmpty(),
    remove = displayValue(BlazeKeymap.Action.Remove).orEmpty(),
    toggleDetails = displayValue(BlazeKeymap.Action.ToggleDetails).orEmpty(),
)
