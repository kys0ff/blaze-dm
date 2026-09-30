package org.blaze.theming.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.ConfigurableBlazeThemeProvider
import org.blaze.theming.api.IslandsDefaults
import org.blaze.theming.api.IslandsPalette
import org.blaze.theming.api.ThemeAppearanceMode
import org.blaze.theming.api.ThemeConfigDefaults
import org.blaze.theming.api.ThemeOption
import org.blaze.theming.api.ThemePalette
import org.blaze.theming.api.mergedWith
import org.blaze.theming.api.withFallback
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Central registry of colour themes: the built-ins bundled with the app plus any plugin
 * jars found in the themes directory. Tracks the active selection, handles plugin install /
 * uninstall / per-theme options, and resolves the palette to apply for a given light/dark mode.
 *
 * Resolution is a strict overlay chain — the design system is never replaced, only retokened:
 * ```
 * selected theme + user option overrides  →  built-in default theme  →  stock Islands tokens
 * ```
 * so a theme (or a user's edit of one color) always composes against a complete palette.
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

    /**
     * The persisted + live theme settings (selection, options, directory). Exposed so the UI can
     * observe per-theme option edits and re-resolve the palette when they change, not just when the
     * selection does — an Apply that only tweaks a color option must restyle the running app.
     */
    val settings: StateFlow<ThemeSettings> = settingsRepository.settings

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

    /** Themes usable in [mode]; a theme that doesn't paint the mode is not offered for it. */
    fun themesFor(mode: ThemeAppearanceMode): List<LoadedTheme> =
        _themes.value.filter {
            it.provider.id == DEFAULT_THEME_ID || it.provider.supportedModes.contains(mode)
        }

    /** The option descriptors a theme publishes; empty for a plain (non-configurable) provider. */
    fun optionDescriptors(id: String): List<ThemeOption> =
        (_themes.value.firstOrNull { it.provider.id == id }?.provider
            as? ConfigurableBlazeThemeProvider)?.optionDescriptors.orEmpty()

    /** The stored option values for one theme, merged over its declared defaults. */
    fun themeConfig(id: String) = runCatching {
        settingsRepository.settings.value.optionValues(id)
            .mergedWith(optionDescriptors(id))
    }.getOrElse {
        ThemeConfigDefaults.fromDescriptors(optionDescriptors(id))
    }

    /**
     * Resolve the palette to apply for the given appearance mode: the active theme's tokens over
     * the built-in default, plus any validated colour the user picked in the theme's Options
     * form, finally completed from the stock Islands design-system values. A theme that does not
     * support [dark]'s mode contributes nothing, so the default theme's palette shows instead.
     */
    fun paletteFor(dark: Boolean): ThemePalette {
        val theme = activeTheme()
        val selected = theme?.provider ?: return stockPalette(dark)

        val mode = if (dark) ThemeAppearanceMode.DARK else ThemeAppearanceMode.LIGHT
        if (selected.id != DEFAULT_THEME_ID && !selected.supportedModes.contains(mode)) {
            return stockPalette(dark)
        }

        // A configurable plugin adopts its persisted answers before painting, so non-color
        // options (a shape toggle, a density select) can influence its own provide() result.
        val descriptors = (selected as? ConfigurableBlazeThemeProvider)?.optionDescriptors.orEmpty()
        val config = themeConfig(selected.id)
        if (selected is ConfigurableBlazeThemeProvider) {
            runCatching { selected.applySettings(config) }
                .onFailure { logger.error("Theme {} failed to applySettings", selected.id, it) }
        }

        val provided = runCatching { selected.provide(dark).sanitized() }
            .getOrElse {
                logger.error("Theme {} failed to provide a palette", selected.id, it)
                ThemePalette.Empty
            }

        // Overlay chain: user's picked colours → theme → default theme → stock Islands tokens.
        val overridden = applyAmbientIntensity(
            applyColorOptions(provided, descriptors, config),
            descriptors,
            config,
        )
        return overridden
            .withFallback(defaultPalette(dark))
            .let { it.copy(islands = it.islands.filledWithIslandsDefaults(dark)) }
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

    /** Store a theme's option answers (the Settings draft's commit path). */
    suspend fun setOptionValues(id: String, values: Map<String, String>) {
        settingsRepository.setOptionValues(id, values)
    }

    /**
     * Stage a theme's option answers into the live settings without writing to disk, so Apply
     * previews them; OK persists. Unknown themes are accepted — the slice is keyed by id and a
     * removed jar's slice simply stops being read.
     */
    fun setOptionValuesInMemory(id: String, values: Map<String, String>) {
        settingsRepository.setOptionValuesInMemory(id, values)
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

    /** The active theme contributes nothing (unknown id, or the mode isn't painted). */
    private fun stockPalette(dark: Boolean): ThemePalette =
        defaultPalette(dark).let { it.copy(islands = it.islands.filledWithIslandsDefaults(dark)) }

    private fun defaultPalette(dark: Boolean): ThemePalette =
        builtIns.firstOrNull { it.id == DEFAULT_THEME_ID }?.provide(dark) ?: ThemePalette.Empty

    /** Complete every unset Islands chrome token from the stock design-system values. */
    private fun IslandsPalette.filledWithIslandsDefaults(dark: Boolean): IslandsPalette =
        withFallback(IslandsDefaults.forMode(dark))

    companion object {
        /** Id of the built-in theme that overrides nothing (the app's stock look). */
        const val DEFAULT_THEME_ID = "default"
    }
}
