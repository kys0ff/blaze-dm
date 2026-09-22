package org.blaze.service.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Configuration of the local download API service.
 *
 * Defaults are secure by construction: the server binds loopback only, authentication is mandatory, and
 * no browser origin is trusted until explicitly allow-listed. A remote host can never reach it without a
 * deliberate (and discouraged) config change plus an auth token.
 */
@Serializable
data class LocalApiSettings(
    val enabled: Boolean = true,
    /** Always loopback in practice; binding elsewhere requires auth and is logged loudly. */
    val host: String = "127.0.0.1",
    /** 0 means "pick an ephemeral free port" and publish it in the service descriptor. */
    val port: Int = 0,
    val requireAuth: Boolean = true,
    /** Browser origins allowed to call the API (exact match). Empty = no web page is trusted. */
    val allowedOrigins: List<String> = emptyList(),
    /** Optional hardening: when non-empty, HTTP-sourced destinations are confined to these roots.
     * When empty the loopback bind + bearer token remain the security boundary. */
    val allowedDownloadRoots: List<String> = emptyList(),
    val maxRequestBytes: Long = 1L * 1024 * 1024,
    val eventMinIntervalMillis: Long = 400,
    val apiLoggingEnabled: Boolean = false,
    val startOnBoot: Boolean = true,
) {
    companion object {
        val DEFAULT = LocalApiSettings()
    }
}

/** JSON-backed store for [LocalApiSettings], mirroring the engine settings' atomic-write approach. */
class LocalApiSettingsRepository(storageDir: Path) {
    private val logger = LoggerFactory.getLogger(LocalApiSettingsRepository::class.java)
    private val file = storageDir.resolve("api.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): LocalApiSettings {
        if (!Files.exists(file)) return LocalApiSettings.DEFAULT
        return runCatching { json.decodeFromString<LocalApiSettings>(Files.readString(file)) }
            .onFailure { logger.warn("Corrupt api.json, falling back to defaults", it) }
            .getOrDefault(LocalApiSettings.DEFAULT)
    }

    suspend fun save(settings: LocalApiSettings) = withContext(Dispatchers.IO) {
        val parent = file.toAbsolutePath().parent ?: return@withContext
        runCatching { Files.createDirectories(parent) }
        val tmp = Files.createTempFile(parent, "api", ".json.tmp")
        try {
            Files.writeString(tmp, json.encodeToString(LocalApiSettings.serializer(), settings))
            runCatching {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.recoverCatching {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            logger.error("Failed to save api.json", e)
        }
    }
}
