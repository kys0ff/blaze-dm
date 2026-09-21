package org.blaze.engine.settings

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

class EngineSettingsRepository(storageDir: Path) {
    private val logger = LoggerFactory.getLogger(EngineSettingsRepository::class.java)
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
        val targetPath = settingsFile.toPath()
        val parentDir = targetPath.parent
        if (parentDir != null) {
            try {
                Files.createDirectories(parentDir)
            } catch (e: Exception) {
                logger.error("Failed to create directories for settings", e)
                return@withContext
            }
        }
        val tmpFile = try {
            Files.createTempFile(parentDir, "settings", ".json.tmp")
        } catch (e: Exception) {
            logger.error("Failed to create temp file for settings", e)
            return@withContext
        }
        try {
            Files.writeString(tmpFile, json.encodeToString(settings))
            try {
                Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            logger.error("Failed to save settings", e)
            Files.deleteIfExists(tmpFile)
        }
    }

    private fun DownloadSettings.validate(): DownloadSettings = copy(
        maxConcurrentDownloads = maxConcurrentDownloads.coerceAtLeast(1),
        maxConnectionsPerDownload = maxConnectionsPerDownload.coerceAtLeast(1),
        maxRetries = maxRetries.coerceAtLeast(0),
        retryDelaySeconds = retryDelaySeconds.coerceAtLeast(0),
        globalSpeedLimitKbps = globalSpeedLimitKbps.coerceAtLeast(1),
        maxRedirects = maxRedirects.coerceIn(0, 20),
        maxPeerConnections = maxPeerConnections.coerceAtLeast(1),
        seedTimeLimitMinutes = seedTimeLimitMinutes.coerceAtLeast(0)
    )
}
