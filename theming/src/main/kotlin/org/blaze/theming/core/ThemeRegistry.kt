package org.blaze.theming.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.ThemePalette
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Central registry of colour themes: the built-ins bundled with the app plus any plugin
 * jars found in the themes directory. Tracks the active selection, handles plugin install /
 * uninstall, and resolves the palette to apply for a given light/dark mode.
 *
 * The registry is UI-framework agnostic: it only ever deals with [ThemePalette] (packed
 * ARGB numbers), never with Compose or Jewel types.
 */
class ThemeRegistry(
    private val builtIns: List<BlazeThemeProvider>,
    private val pluginLoader: ThemePluginLoader,
    private val settingsRepository: ThemeSettingsRepository
) {
    private val logger = LoggerFactory.getLogger(ThemeRegistry::class.java)

    private val _themes = MutableStateFlow<List<LoadedTheme>>(emptyList())
    /** All discovered themes (built-in + plugin). */
    val themes: StateFlow<List<LoadedTheme>> = _themes.asStateFlow()

    private val _selectedThemeId = MutableStateFlow(settingsRepository.settings.value.selectedThemeId)
    /** Id of the active theme. */
    val selectedThemeId: StateFlow<String> = _selectedThemeId.asStateFlow()

    // Kept as a field so the plugin class loaders (and their loaded classes) are not
    // garbage-collected while their themes are still registered.
    @Suppress("unused")
    private var pluginLoaders: List<URLClassLoader> = emptyList()

    init {
        reload()
    }

    /** Re-scan the themes directory and rebuild the theme list, keeping the selection valid. */
    fun reload() {
        val result = pluginLoader.load(themesDir())
        pluginLoaders = result.classLoaders

        val merged = buildList {
            addAll(builtIns.map { LoadedTheme(it, ThemeSource.BUILTIN) })
            addAll(result.themes)
        }
        _themes.update { merged }

        // If the selected theme vanished (jar removed), fall back to the built-in default.
        val selected = _selectedThemeId.value
        if (merged.none { it.provider.id == selected }) {
            _selectedThemeId.update { DEFAULT_THEME_ID }
        }
    }

    fun themesDir(): Path = Path.of(settingsRepository.settings.value.extensionDir)

    /** The active theme record, or the built-in default when the selection is unknown. */
    fun activeTheme(): LoadedTheme? =
        _themes.value.firstOrNull { it.provider.id == _selectedThemeId.value }
            ?: _themes.value.firstOrNull { it.provider.id == DEFAULT_THEME_ID }

    /**
     * Resolve the palette to apply for the given appearance mode. When the selected theme
     * doesn't know a colour, the built-in default theme's value is used (which may itself be
     * null, letting the UI keep its hard-coded fallback) — so partial themes compose cleanly.
     */
    fun paletteFor(dark: Boolean): ThemePalette {
        val selected = activeTheme()?.provider?.provide(dark) ?: ThemePalette.Empty
        val fallback = builtIns.firstOrNull { it.id == DEFAULT_THEME_ID }?.provide(dark) ?: ThemePalette.Empty
        return selected.withFallback(fallback)
    }

    /** Point the app at the theme with [id], persisting the choice. Unknown ids are ignored. */
    suspend fun select(id: String) {
        if (_themes.value.none { it.provider.id == id }) {
            logger.warn("Ignoring selection of unknown theme {}", id)
            return
        }
        _selectedThemeId.update { id }
        settingsRepository.updateSettings { it.copy(selectedThemeId = id) }
    }

    /**
     * Restyle the running app by pointing at the theme with [id] without writing to disk.
     * The settings screen's Apply button uses this; only OK persists the choice.
     */
    fun selectInMemory(id: String) {
        if (_themes.value.none { it.provider.id == id }) {
            logger.warn("Ignoring selection of unknown theme {}", id)
            return
        }
        _selectedThemeId.update { id }
    }

    /** Undo an unpersisted [selectInMemory] by reverting to the theme stored on disk. */
    fun restoreSelectionFromSettings() {
        _selectedThemeId.update { settingsRepository.settings.value.selectedThemeId }
    }

    /** Copy [jar] into the themes directory and reload. */
    suspend fun install(jar: Path): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = themesDir()
            Files.createDirectories(dir)
            val target = dir.resolve(jar.fileName.toString())
            Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING)
            reload()
        }.onFailure { logger.error("Failed to install theme {}", jar, it) }
    }

    /** Delete a theme jar and reload. Built-ins cannot be removed. */
    suspend fun uninstall(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val theme = _themes.value.firstOrNull { it.provider.id == id }
        when {
            theme == null -> Result.failure(IllegalStateException("Unknown theme"))
            theme.pluginPath == null -> Result.failure(IllegalStateException("Built-in themes cannot be removed"))
            else -> runCatching {
                Files.deleteIfExists(theme.pluginPath)
                reload()
            }.onFailure { logger.error("Failed to remove theme {}", id, it) }
        }
    }

    private fun ThemePalette.withFallback(fallback: ThemePalette) = ThemePalette(
        accentArgb = accentArgb ?: fallback.accentArgb,
        successArgb = successArgb ?: fallback.successArgb,
        warningArgb = warningArgb ?: fallback.warningArgb,
        errorArgb = errorArgb ?: fallback.errorArgb,
        hoverArgb = hoverArgb ?: fallback.hoverArgb,
        panelBackgroundArgb = panelBackgroundArgb ?: fallback.panelBackgroundArgb,
        borderArgb = borderArgb ?: fallback.borderArgb,
        infoTextArgb = infoTextArgb ?: fallback.infoTextArgb,
    )

    companion object {
        /** Id of the built-in theme that overrides nothing (the app's stock look). */
        const val DEFAULT_THEME_ID = "default"
    }
}
