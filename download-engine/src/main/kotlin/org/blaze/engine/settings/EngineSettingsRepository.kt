package org.blaze.engine.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Path

class EngineSettingsRepository(storageDir: Path) {
    private val settingsFile = storageDir.resolve("settings.json").toFile()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<DownloadSettings> = _settings.asStateFlow()

    private fun loadSettings(): DownloadSettings {
        if (!settingsFile.exists()) return DownloadSettings()
        return try {
            json.decodeFromString<DownloadSettings>(settingsFile.readText())
        } catch (_: Exception) {
            DownloadSettings()
        }
    }

    suspend fun updateSettings(transform: (DownloadSettings) -> DownloadSettings) {
        val newSettings = transform(_settings.value).validate()
        _settings.update { newSettings }
        saveSettings(newSettings)
    }

    private suspend fun saveSettings(settings: DownloadSettings) = withContext(Dispatchers.IO) {
        try {
            settingsFile.parentFile.mkdirs()
            settingsFile.writeText(json.encodeToString(settings))
        } catch (e: Exception) {
            println("Failed to save settings: ${e.message}")
        }
    }

    private fun DownloadSettings.validate(): DownloadSettings = copy(
        maxConcurrentDownloads = maxConcurrentDownloads.coerceAtLeast(1),
        maxConnectionsPerDownload = maxConnectionsPerDownload.coerceAtLeast(1),
        maxRetries = maxRetries.coerceAtLeast(0),
        retryDelaySeconds = retryDelaySeconds.coerceAtLeast(0),
        globalSpeedLimitKbps = globalSpeedLimitKbps.coerceAtLeast(1)
    )
}
