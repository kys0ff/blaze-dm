package org.blaze.resolver.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persisted configuration for the link-handler subsystem. Stored separately from the
 * engine's `settings.json` so extensions can evolve independently.
 */
@Serializable
data class ResolverSettings(
    /** Absolute path to the directory plugin jars are loaded from. */
    val extensionDir: String = "",
    /** When more than one handler matches, let the user pick; otherwise use the first one silently. */
    val alwaysAskHandler: Boolean = true,
    /** Ids of handlers the user switched off. Everything not listed is enabled. */
    val disabledHandlers: List<String> = emptyList()
)

class LinkResolverSettingsRepository(private val storageDir: Path) {
    private val logger = LoggerFactory.getLogger(LinkResolverSettingsRepository::class.java)
    private val settingsFile = storageDir.resolve("resolvers.json").toFile()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val defaults: ResolverSettings
        get() = ResolverSettings(extensionDir = storageDir.resolve("extensions").toString())

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ResolverSettings> = _settings.asStateFlow()

    private fun loadSettings(): ResolverSettings {
        if (!settingsFile.exists()) return defaults
        return try {
            json.decodeFromString<ResolverSettings>(settingsFile.readText())
                .let { if (it.extensionDir.isBlank()) it.copy(extensionDir = defaults.extensionDir) else it }
        } catch (_: Exception) {
            defaults
        }
    }

    suspend fun updateSettings(transform: (ResolverSettings) -> ResolverSettings) {
        val newSettings = transform(_settings.value)
        _settings.update { newSettings }
        saveSettings(newSettings)
    }

    private suspend fun saveSettings(settings: ResolverSettings) = withContext(Dispatchers.IO) {
        val targetPath = settingsFile.toPath()
        val parentDir = targetPath.parent
        try {
            if (parentDir != null) Files.createDirectories(parentDir)
            val tmpFile = Files.createTempFile(parentDir, "resolvers", ".json.tmp")
            try {
                Files.writeString(tmpFile, json.encodeToString(settings))
                try {
                    Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                logger.error("Failed to save resolver settings: ${e.message}")
                Files.deleteIfExists(tmpFile)
            }
        } catch (e: Exception) {
            logger.error("Failed to save resolver settings: ${e.message}")
        }
    }
}
