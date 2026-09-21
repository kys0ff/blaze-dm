package org.blaze.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists app-shell (tray / autostart) settings to `app.json` in the shared storage
 * directory. Mirrors [org.blaze.engine.settings.EngineSettingsRepository]'s deferred
 * persistence semantics: the settings screen's Apply edits the live in-memory value only,
 * OK writes to disk, and leaving the screen without OK reverts via [revertToPersisted].
 */
class AppSettingsRepository(storageDir: Path) {
    private val logger = LoggerFactory.getLogger(AppSettingsRepository::class.java)
    private val settingsFile = storageDir.resolve("app.json").toFile()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // The value as it exists on disk; in-memory-only edits are reverted back to this.
    private var persistedSettings: AppSettings = _settings.value

    /** The last settings committed to disk, e.g. for reverting an unpersisted Apply. */
    val persisted: AppSettings get() = persistedSettings

    private fun loadSettings(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        return try {
            json.decodeFromString<AppSettings>(settingsFile.readText())
        } catch (_: Exception) {
            AppSettings()
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val newSettings = transform(_settings.value)
        persistedSettings = newSettings
        _settings.update { newSettings }
        saveSettings(newSettings)
    }

    /** Update the in-memory settings so live consumers react, without writing to disk. */
    fun updateSettingsInMemory(transform: (AppSettings) -> AppSettings) {
        _settings.update { transform(it) }
    }

    /** Discard any in-memory (Apply) changes and restore the last persisted settings. */
    fun revertToPersisted() {
        _settings.update { persistedSettings }
    }

    private suspend fun saveSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        val targetPath = settingsFile.toPath()
        val parentDir = targetPath.parent
        try {
            if (parentDir != null) Files.createDirectories(parentDir)
            val tmpFile = Files.createTempFile(parentDir, "app", ".json.tmp")
            try {
                Files.writeString(tmpFile, json.encodeToString(settings))
                try {
                    Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                logger.error("Failed to save app settings", e)
                Files.deleteIfExists(tmpFile)
            }
        } catch (e: Exception) {
            logger.error("Failed to save app settings", e)
        }
    }
}
