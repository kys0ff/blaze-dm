package org.blaze.resolver.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Central registry of link handlers: the built-ins bundled with the app plus any plugin
 * jars found in the extensions directory. Handles enable/disable state, plugin install /
 * uninstall and the actual (blocking) resolve call, dispatched onto an IO thread.
 */
class LinkResolverRegistry(
    private val builtIns: List<LinkResolver>,
    private val pluginLoader: PluginLoader,
    private val settingsRepository: LinkResolverSettingsRepository
) {
    private val logger = LoggerFactory.getLogger(LinkResolverRegistry::class.java)

    private val _handlers = MutableStateFlow<List<LoadedResolver>>(emptyList())
    /** All discovered handlers (built-in + plugin), regardless of enabled state. */
    val handlers: StateFlow<List<LoadedResolver>> = _handlers.asStateFlow()

    // Kept as a field so the plugin class loaders (and their loaded classes) are not
    // garbage-collected while their resolvers are still registered.
    @Suppress("unused")
    private var pluginLoaders: List<URLClassLoader> = emptyList()

    init {
        reload()
    }

    /** Re-scan the extensions directory and rebuild the handler list. */
    fun reload() {
        val dir = extensionDir()
        val result = pluginLoader.load(dir)
        pluginLoaders = result.classLoaders

        val merged = buildList {
            addAll(builtIns.map { LoadedResolver(it, ResolverSource.BUILTIN) })
            addAll(result.resolvers)
        }
        _handlers.update { merged }
    }

    fun extensionDir(): Path =
        Path.of(settingsRepository.settings.value.extensionDir)

    fun isEnabled(id: String): Boolean =
        id !in settingsRepository.settings.value.disabledHandlers

    /** Enabled handlers that claim they can resolve [url]. */
    fun enabledHandlers(url: String): List<LoadedResolver> =
        _handlers.value.filter { isEnabled(it.resolver.id) && runCatching { it.resolver.canResolve(url) }.getOrDefault(false) }

    /** Run a handler's blocking resolve off the UI thread, wrapping any failure. */
    suspend fun resolve(handler: LoadedResolver, url: String): Result<ResolvedLink> =
        withContext(Dispatchers.IO) {
            runCatching { handler.resolver.resolve(url) }
        }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        settingsRepository.updateSettings { settings ->
            val disabled = settings.disabledHandlers.toMutableSet()
            if (enabled) disabled.remove(id) else disabled.add(id)
            settings.copy(disabledHandlers = disabled.toList())
        }
    }

    suspend fun setAlwaysAsk(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(alwaysAskHandler = enabled) }
    }

    /** Copy [jar] into the extensions directory and reload. */
    suspend fun install(jar: Path): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = extensionDir()
            Files.createDirectories(dir)
            val target = dir.resolve(jar.fileName.toString())
            Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING)
            reload()
        }.onFailure { logger.error("Failed to install extension: ${it.message}") }
    }

    /** Delete a plugin jar and reload. Built-ins cannot be removed. */
    suspend fun uninstall(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val handler = _handlers.value.firstOrNull { it.resolver.id == id }
        when {
            handler == null -> Result.failure(IllegalStateException("Unknown handler"))
            handler.pluginPath == null -> Result.failure(IllegalStateException("Built-in handlers cannot be removed"))
            else -> runCatching {
                Files.deleteIfExists(handler.pluginPath)
                reload()
            }.onFailure { logger.error("Failed to remove extension: ${it.message}") }
        }
    }
}
