package org.island.theme.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.island.theme.api.ThemeConfig
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persisted configuration for the theming subsystem. Stored separately from the engine's
 * `settings.json` and the resolvers' `resolvers.json` so themes can evolve independently.
 */
@Serializable
data class ThemeSettings(
    /** Absolute path to the directory theme jars are loaded from. */
    val extensionDir: String = "",
    /** Id of the active colour theme. Defaults to the built-in theme. */
    val selectedThemeId: String = ThemeRegistry.DEFAULT_THEME_ID,
    /**
     * Per-theme answers to a [org.island.theme.api.ConfigurableIslandThemeProvider]'s options,
     * keyed by theme id then by option key. Plain strings (like the resolver slices) so a new
     * option type never changes the file schema; a key the user never touched falls back to the
     * descriptor's default.
     */
    val themeOptions: Map<String, Map<String, String>> = emptyMap(),
) {
    /** The stored option values for one theme, as a [ThemeConfig]. */
    fun optionValues(themeId: String): ThemeConfig = ThemeConfig(themeOptions[themeId].orEmpty())
}

class ThemeSettingsRepository(private val storageDir: Path) {
    private val logger = LoggerFactory.getLogger(ThemeSettingsRepository::class.java)
    private val settingsFile = storageDir.resolve("themes.json").toFile()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val defaults: ThemeSettings
        get() = ThemeSettings(extensionDir = storageDir.resolve("themes").toString())

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    // The value as it exists on disk; in-memory-only (Settings Apply) edits are reverted back to
    // this when the screen is abandoned, so an unpersisted Apply never leaks.
    private var persistedSettings: ThemeSettings = _settings.value

    private fun loadSettings(): ThemeSettings {
        if (!settingsFile.exists()) return defaults
        return try {
            json.decodeFromString<ThemeSettings>(settingsFile.readText())
                .let { if (it.extensionDir.isBlank()) it.copy(extensionDir = defaults.extensionDir) else it }
        } catch (_: Exception) {
            defaults
        }
    }

    suspend fun updateSettings(transform: (ThemeSettings) -> ThemeSettings) {
        val newSettings = transform(_settings.value)
        persistedSettings = newSettings
        _settings.update { newSettings }
        saveSettings(newSettings)
    }

    /** Discard any in-memory (Apply) changes and restore the last persisted settings. */
    fun revertToPersisted() {
        _settings.update { persistedSettings }
    }

    /**
     * Replace one theme's option values on disk. An empty [values] removes the theme's slice so a
     * reinstalled jar starts from its declared defaults again.
     */
    suspend fun setOptionValues(themeId: String, values: Map<String, String>) =
        updateSettings { settings ->
            val slices = settings.themeOptions.toMutableMap()
            if (values.isEmpty()) slices.remove(themeId) else slices[themeId] = values
            settings.copy(themeOptions = slices)
        }

    /** Same write as [setOptionValues] but in memory only - a Settings Apply preview. */
    fun setOptionValuesInMemory(themeId: String, values: Map<String, String>) {
        _settings.update { settings ->
            val slices = settings.themeOptions.toMutableMap()
            if (values.isEmpty()) slices.remove(themeId) else slices[themeId] = values
            settings.copy(themeOptions = slices)
        }
    }

    private suspend fun saveSettings(settings: ThemeSettings) = withContext(Dispatchers.IO) {
        val targetPath = settingsFile.toPath()
        val parentDir = targetPath.parent
        try {
            if (parentDir != null) Files.createDirectories(parentDir)
            val tmpFile = Files.createTempFile(parentDir, "themes", ".json.tmp")
            try {
                Files.writeString(tmpFile, json.encodeToString(settings))
                try {
                    Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                logger.error("Failed to save theme settings", e)
                Files.deleteIfExists(tmpFile)
            }
        } catch (e: Exception) {
            logger.error("Failed to save theme settings", e)
        }
    }
}
