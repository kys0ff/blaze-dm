package org.blaze.presentation.screens.extensions

/**
 * Actions the live Extensions tool-window panel can raise. Unlike the Settings dialog — where
 * enable/option edits are buffered until Apply/OK — every event here takes effect immediately,
 * mirroring an IDE tool window: the panel edits the running configuration directly.
 */
sealed interface ExtensionsEvent {
    /** Switch a handler on or off; its matching links stop/return at once. */
    data class Toggle(val id: String, val enabled: Boolean) : ExtensionsEvent

    /** Persist a handler's whole option map right away (the options dialog's OK). */
    data class SaveOptions(val id: String, val values: Map<String, String>) : ExtensionsEvent

    /** Reorder a handler among the matching ones; higher resolves first. */
    data class SetPriority(val id: String, val priority: Int) : ExtensionsEvent

    /** Copy a jar into the extensions directory and rescan. */
    data class Install(val jarPath: String) : ExtensionsEvent

    /** Delete a plugin jar and rescan. Built-ins cannot be removed. */
    data class Uninstall(val id: String) : ExtensionsEvent

    /** Rescan the extensions directory, rebuilding the handler list from disk. */
    data object Reload : ExtensionsEvent
}
