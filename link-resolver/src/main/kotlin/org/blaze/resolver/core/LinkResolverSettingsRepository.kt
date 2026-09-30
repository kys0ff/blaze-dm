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
 * One handler's persisted slice of the resolver configuration: the option values the user
 * chose, the priority that orders it against other matching handlers, and its enabled
 * switch. Everything lives under the handler's id in `resolvers.json`, so each extension
 * owns exactly one isolated slice and a broken value can never bleed into another handler.
 */
@Serializable
data class ResolverOptionsSlice(
    /** Option values keyed by `ResolverOption.key`; absent means "descriptor default". */
    val optionValues: Map<String, String> = emptyMap(),
    /** Higher runs first when several handlers match; ties fall back to display name. */
    val priority: Int = 0,
    /** The enable switch. The legacy top-level [ResolverSettings.disabledHandlers] list
     * migrates into `enabled = false` slices on first load. */
    val enabled: Boolean = true,
)

/**
 * Persisted configuration for the link-handler subsystem. Stored separately from the
 * engine's `settings.json` so extensions can evolve independently: subsystem-wide
 * preferences here, per-handler state in [handlerOptions], and per-option types in the
 * API layer (`org.blaze.resolver.api`).
 */
@Serializable
data class ResolverSettings(
    /** Absolute path to the directory plugin jars are loaded from. */
    val extensionDir: String = "",
    /** When more than one handler matches, let the user pick; otherwise use the first one silently. */
    val alwaysAskHandler: Boolean = true,
    /** Timeout, in seconds, the subsystem hands to handlers for their HTTP requests. */
    val requestTimeoutSeconds: Int = 15,
    /** Legacy enable state (ids switched off). Read for old files and folded into
     * [handlerOptions] slices on load; new writes keep it in sync for external readers. */
    val disabledHandlers: List<String> = emptyList(),
    /** Per-handler configuration, keyed by `LinkResolver.id`. */
    val handlerOptions: Map<String, ResolverOptionsSlice> = emptyMap(),
) {
    /** Ids currently switched off: the legacy list unioned with disabled slices. */
    val disabledHandlerIds: Set<String>
        get() = disabledHandlers.toSet() + handlerOptions
            .filterValues { !it.enabled }
            .keys

    fun isEnabled(id: String): Boolean = id !in disabledHandlerIds

    fun slice(id: String): ResolverOptionsSlice = handlerOptions[id] ?: ResolverOptionsSlice()

    /** Effective disabled list written back into the legacy field for old readers. */
    fun withDisabledHandlers(ids: List<String>): ResolverSettings = copy(disabledHandlers = ids)
}

class LinkResolverSettingsRepository(private val storageDir: Path) {
    private val logger = LoggerFactory.getLogger(LinkResolverSettingsRepository::class.java)
    private val settingsFile = storageDir.resolve("resolvers.json").toFile()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val defaults: ResolverSettings
        get() = ResolverSettings(extensionDir = storageDir.resolve("extensions").toString())

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ResolverSettings> = _settings.asStateFlow()

    // The value as it exists on disk; in-memory-only edits are reverted back to this.
    private var persistedSettings: ResolverSettings = _settings.value

    private fun loadSettings(): ResolverSettings = readFromDisk().migrated()

    private fun readFromDisk(): ResolverSettings {
        if (!settingsFile.exists()) return defaults
        return try {
            json.decodeFromString<ResolverSettings>(settingsFile.readText())
                .let { if (it.extensionDir.isBlank()) it.copy(extensionDir = defaults.extensionDir) else it }
        } catch (_: Exception) {
            defaults
        }
    }

    /**
     * One-shot migration: every id on the legacy `disabledHandlers` list that has no slice
     * yet becomes an `enabled = false` slice. The list itself is kept as the canonical
     * switch-off record (Settings' draft flow and old readers both use it), while
     * [ResolverSettings.disabledHandlerIds] unions both sources.
     */
    private fun ResolverSettings.migrated(): ResolverSettings {
        if (disabledHandlers.isEmpty()) return this
        val slices = handlerOptions.toMutableMap()
        disabledHandlers.forEach { id ->
            slices.putIfAbsent(id, ResolverOptionsSlice(enabled = false))
        }
        return copy(handlerOptions = slices)
    }

    /** Rewrites [block] over the settings, persisting the result. */
    suspend fun updateSettings(transform: (ResolverSettings) -> ResolverSettings) {
        val newSettings = transform(_settings.value)
        persistedSettings = newSettings
        _settings.update { newSettings }
        saveSettings(newSettings)
    }

    /** Update the in-memory settings so live consumers react, without writing to disk. */
    fun updateSettingsInMemory(transform: (ResolverSettings) -> ResolverSettings) {
        _settings.update { transform(it) }
    }

    /** Discard any in-memory (Apply) changes and restore the last persisted settings. */
    fun revertToPersisted() {
        _settings.update { persistedSettings }
    }

    // ---- per-handler edits ----------------------------------------------------
    // [persist] = false buffers the change in memory only (the Settings dialog's draft
    // flow commits later); the live panel edits persist immediately.

    /** Switch a handler on or off: the slice is the per-handler record, and the legacy
     * list is kept consistent so readers of either shape agree. */
    suspend fun setEnabled(id: String, enabled: Boolean, persist: Boolean = true) {
        val write: (ResolverSettings) -> ResolverSettings = { settings ->
            val slice = settings.slice(id).copy(enabled = enabled)
            val legacy = settings.disabledHandlers.toMutableSet().apply {
                if (enabled) remove(id) else add(id)
            }
            settings.copy(
                handlerOptions = settings.handlerOptions + (id to slice),
                disabledHandlers = legacy.sorted(),
            )
        }
        if (persist) updateSettings(write) else updateSettingsInMemory(write)
    }

    /** Store one option value inside the handler's slice. */
    suspend fun setOptionValue(id: String, key: String, value: String, persist: Boolean = true) =
        editSlice(id, { s -> s.copy(optionValues = s.optionValues + (key to value)) }, persist)

    /** Remove one option value so the descriptor default applies again. */
    suspend fun clearOptionValue(id: String, key: String, persist: Boolean = true) =
        editSlice(id, { s -> s.copy(optionValues = s.optionValues - key) }, persist)

    /** Reorder a handler among the matching ones. */
    suspend fun setPriority(id: String, priority: Int, persist: Boolean = true) =
        editSlice(id, { it.copy(priority = priority) }, persist)

    /** Replace a handler's whole option map (the options dialog's OK). */
    suspend fun setOptionValues(id: String, values: Map<String, String>, persist: Boolean = true) =
        editSlice(id, { it.copy(optionValues = values) }, persist)

    private suspend fun editSlice(
        id: String,
        transform: (ResolverOptionsSlice) -> ResolverOptionsSlice,
        persist: Boolean,
    ) {
        val write: (ResolverSettings) -> ResolverSettings = { settings ->
            val current = settings.slice(id)
            settings.copy(handlerOptions = settings.handlerOptions + (id to transform(current)))
        }
        if (persist) updateSettings(write) else updateSettingsInMemory(write)
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
                logger.error("Failed to save resolver settings", e)
                Files.deleteIfExists(tmpFile)
            }
        } catch (e: Exception) {
            logger.error("Failed to save resolver settings", e)
        }
    }
}
