package org.blaze.presentation.components

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
 * The app's one keymap: what an action is bound to, how that binding is recognised, and how it reads
 * on this OS.
 *
 * Before this existed the accelerator text was assembled per call site (`shortcutOf("A")` here, a
 * literal "Del" there) while the key that was actually listened for was spelled out again inside the
 * screen's key handler. Two copies of the same fact, nothing keeping them honest: a menu could print
 * a chord no code ever handled — Settings was exactly that, labelled everywhere and bound nowhere.
 *
 * Here a binding is a *fact about the keyboard* held once, and both ends read it: [matches] is what a
 * key handler asks, [displayValue] is what a menu row prints. A label can now only appear next to an
 * action if the same object answers the keypress.
 *
 * macOS shows the command glyph with no separator, Windows and Linux spell the modifiers out — the
 * same split the dialog footers use, off the single [CurrentUiPlatform] switch.
 */
object BlazeKeymap {

    /** Every action that has an accelerator somewhere in the chrome. */
    enum class Action(val binding: Binding?) {
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

        // TODO: make use of hasBinding
        val hasBinding: Boolean get() = binding != null
    }

    /** Which physical key, under which modifiers. `ctrl` means the OS's primary modifier: Ctrl on
     * Windows and Linux, Command on macOS — matching how the handlers already read it. */
    data class Binding(
        val key: Key,
        val ctrl: Boolean = true,
        val shift: Boolean = false,
        val alt: Boolean = false,
    )

    /** Whether [event] is a press of this binding. The modifier set has to match exactly, so Ctrl+Alt+F
     * does not read as the app's Ctrl+F, and a bare key does not fire a chord that needs modifiers. */
    fun Binding.matches(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key != key) return false
        val primary = event.isCtrlPressed || event.isMetaPressed
        if (primary != ctrl || event.isShiftPressed != shift || event.isAltPressed != alt) return false
        return true
    }

    /** The action [event] triggers, or null when no accelerator claims it — the whole job of a shell
     * key handler that wants to stay in sync with what the menus print. */
    fun matchAction(event: KeyEvent): Action? =
        Action.entries.firstOrNull { it.binding != null && it.binding.matches(event) }

    /**
     * The label for [action] — "Ctrl+A", "⌘A", "Del" — or null when the action has no binding, which
     * is how a menu row leaves the accelerator column empty instead of inventing one.
     */
    fun displayValue(action: Action): String? = action.binding?.let { format(it) }

    private fun format(binding: Binding): String {
        val modifiers = if (CurrentUiPlatform == UiPlatform.macOS) {
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

    private fun keyName(key: Key): String = when (key) {
        Key.N -> "N"
        Key.K -> "K"
        Key.F -> "F"
        Key.I -> "I"
        Key.A -> "A"
        Key.S -> "S"
        Key.Spacebar -> "Space"
        Key.Enter -> if (CurrentUiPlatform == UiPlatform.macOS) "↩" else "Enter"
        Key.Delete -> if (CurrentUiPlatform == UiPlatform.macOS) "⌦" else "Del"
        // Every binding the app declares is listed above; an unlisted key would print its modifiers
        // alone, which is the signal to extend this map rather than to guess a name here.
        else -> ""
    }
}

/**
 * Builds the bundle of shortcut strings the chrome reads from [LocalShortcutLabels], from the one
 * keymap rather than from per-screen literals.
 */
fun platformShortcutLabels(): ShortcutLabels = ShortcutLabels(
    newDownload = BlazeKeymap.displayValue(BlazeKeymap.Action.NewDownload).orEmpty(),
    search = BlazeKeymap.displayValue(BlazeKeymap.Action.FocusListSearch).orEmpty(),
    // The IDE's Search Everywhere is Cmd/Ctrl+N in IntelliJ (where that is "New Project"); here N is
    // taken by New Download, so the popup keeps the IDE's second, undisputed binding.
    quickSearch = BlazeKeymap.displayValue(BlazeKeymap.Action.SearchEverywhere).orEmpty(),
    selectAll = BlazeKeymap.displayValue(BlazeKeymap.Action.SelectAll).orEmpty(),
    settings = BlazeKeymap.displayValue(BlazeKeymap.Action.OpenSettings).orEmpty(),
    togglePause = BlazeKeymap.displayValue(BlazeKeymap.Action.TogglePause).orEmpty(),
    remove = BlazeKeymap.displayValue(BlazeKeymap.Action.Remove).orEmpty(),
    toggleDetails = BlazeKeymap.displayValue(BlazeKeymap.Action.ToggleDetails).orEmpty(),
)
