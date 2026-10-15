package org.blaze.presentation.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import org.island.theme.ui.ShortcutLabels
import org.island.ui.keymap.IslandKeyBinding
import org.island.ui.keymap.IslandKeymap
import org.island.ui.keymap.captureIslandBinding
import org.island.ui.keymap.isAcceptableIslandBinding

/**
 * The app's one keymap: which action is bound to which chord, and how that chord reads on this OS.
 *
 * The generic half - the binding model, exact modifier matching, chord capture, per-OS label
 * formatting and the live override registry - lives in `:island-ui` ([IslandKeymap] over an
 * app-supplied action type). What stays here is Blaze's own vocabulary: the [Action] enum and its
 * built-in defaults. Every read (matching, the printed accelerator, the Settings preview) resolves
 * through the engine, so a label can only appear next to an action if the same object answers the
 * keypress.
 *
 * The built-in [Action.defaultBinding] is only the starting point. Users may rebind any action from
 * Settings, so every read goes through [effectiveBinding] - the live override if one exists,
 * otherwise the default. The overrides are held in snapshot state, so a rebind updates the handlers
 * *and* every menu row, tooltip and search result that prints the chord, without a restart.
 */
object BlazeKeymap {

    /** Every action that can carry an accelerator somewhere in the chrome. */
    enum class Action(val defaultBinding: IslandKeyBinding?) {
        NewDownload(IslandKeyBinding(Key.N)),
        SearchEverywhere(IslandKeyBinding(Key.K)),
        FocusListSearch(IslandKeyBinding(Key.F)),
        ToggleDetails(IslandKeyBinding(Key.I)),
        SelectAll(IslandKeyBinding(Key.A)),

        // The row handler only fires this with a selection present; the keymap records the key, the
        // screen keeps its own precondition.
        TogglePause(IslandKeyBinding(Key.Spacebar)),
        Remove(IslandKeyBinding(Key.Delete, ctrl = false)),

        // Bare Enter opens the one selected row, so the menu's "Open File" can show it.
        OpenSelected(IslandKeyBinding(Key.Enter, ctrl = false)),

        // Bound by the shell only when Settings can be reached, and spelled out because it does not
        // fit the single-modifier pattern the other bindings use.
        OpenSettings(IslandKeyBinding(Key.S, alt = true)),

        /** Menu rows with no real binding. They show no accelerator rather than an imaginary one. */
        None(null);

        /** Whether this action is offered in the Shortcuts settings page (everything but the null row). */
        val bindable: Boolean get() = this != None
    }

    /** The bindable actions, in the order the Shortcuts settings page lists them. */
    val bindableActions: List<Action> = Action.entries.filter { it.bindable }

    private val engine = IslandKeymap(bindableActions) { it.defaultBinding }

    /** The binding actually in force for [action]: the user override, else the default, else none. */
    fun effectiveBinding(action: Action): IslandKeyBinding? = engine.effectiveBinding(action)

    /** Whether the user has explicitly unbound [action] (as opposed to it simply keeping its default). */
    fun isCleared(action: Action): Boolean = engine.isCleared(action)

    /** Whether [action] currently runs on a user override rather than its built-in default. */
    fun hasOverride(action: Action): Boolean = engine.hasOverride(action)

    /** Replace the whole live override set - the shape the persisted keymap and the settings draft reduce to. */
    fun replaceOverrides(overrides: Map<Action, IslandKeyBinding>, cleared: Set<Action>) =
        engine.replaceOverrides(overrides, cleared)

    /** The action [event] triggers, or null when no accelerator claims it. */
    fun matchAction(event: KeyEvent): Action? = engine.matchAction(event)

    /** The live label for [action], or null when the action has no effective binding. */
    fun displayValue(action: Action): String? = engine.displayValue(action)

    /** Formats a single [binding] (or null) the same way a live label reads. */
    fun format(binding: IslandKeyBinding?): String? = engine.format(binding)

    /** The [IslandKeyBinding] a recorder would store for [event], or null for a non-bindable press. */
    fun capture(event: KeyEvent): IslandKeyBinding? = captureIslandBinding(event)

    /** Whether a binding is safe to accept without stealing ordinary text entry. */
    fun isAcceptable(binding: IslandKeyBinding): Boolean = isAcceptableIslandBinding(binding)
}

/**
 * Builds the bundle of shortcut strings the chrome reads from [org.island.theme.ui.LocalShortcutLabels],
 * from the one keymap rather than from per-screen literals. It reads live bindings through
 * [BlazeKeymap.displayValue], so calling it inside a composition subscribes that composition to
 * rebind changes.
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
