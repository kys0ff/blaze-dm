package org.blaze.presentation.components

/**
 * The single place the UI learns which modifier key the current desktop uses for accelerators.
 *
 * Compose Desktop is JVM-only, so this is a plain `when` on `os.name` (the same pattern the
 * `:platform` module's service factories use) rather than expect/actual. Everything that needs to
 * show a shortcut — tooltips, context menus, the settings tree — reads the formatted strings from
 * [platformShortcutLabels] instead of hard-coding "Ctrl" vs "Cmd".
 */
internal enum class UiPlatform { Windows, macOS, Linux } // TODO: Warning: Enum entry name 'macOS' should start with an uppercase letter

internal val CurrentUiPlatform: UiPlatform by lazy {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    when {
        os.contains("mac") || os.contains("darwin") -> UiPlatform.macOS
        os.startsWith("windows") -> UiPlatform.Windows
        else -> UiPlatform.Linux
    }
}

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
