package org.blaze.presentation.components

/**
 * The accelerator labels the chrome shows, formatted for the desktop this app is running on.
 *
 * The platform itself is decided once, by
 * [CurrentUiPlatform][org.blaze.presentation.components.CurrentUiPlatform] (shared with the dialog
 * footers, which order their buttons off the same switch). Everything that needs to show a shortcut —
 * tooltips, context menus, the settings tree — reads the formatted strings from
 * [platformShortcutLabels] instead of hard-coding "Ctrl" vs "Cmd".
 */

/** Primary accelerator modifier: "Cmd" on macOS, "Ctrl" elsewhere (matches the IDE). */
internal val ModifierKeyName: String
    get() = when (CurrentUiPlatform) {
        UiPlatform.macOS -> "Cmd"
        else -> "Ctrl"
    }

/** Formats one accelerator as the IDE shows it, e.g. "Ctrl N" / "Cmd N" / "Ctrl⇧N". */
internal fun shortcutOf(vararg keys: String): String =
    (listOf(ModifierKeyName) + keys).joinToString(if (CurrentUiPlatform == UiPlatform.macOS) "" else " ")

/** Builds the app-wide shortcut labels bundle for the current platform. */
internal fun platformShortcutLabels(): ShortcutLabels = ShortcutLabels(
    newDownload = shortcutOf("N"),
    search = shortcutOf("F"),
    selectAll = shortcutOf("A"),
    settings = if (CurrentUiPlatform == UiPlatform.macOS) "Cmd," else "Ctrl+Alt+S",
    togglePause = shortcutOf("Space"),
    remove = "Del",
)
