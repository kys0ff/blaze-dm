package org.blaze.resolver.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.blaze.resolver.api.ConfigurableLinkResolver
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverConfig
import org.blaze.resolver.api.ResolverContext
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.api.mergedWith
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

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
        settingsRepository.settings.value.isEnabled(id)

    /**
     * Enabled handlers that claim they can resolve [url], best-first: a user-set priority
     * orders them, display name breaks ties stably. The "use the first one silently" path
     * of always-ask-off therefore honors the handler the user prefers.
     */
    fun enabledHandlers(url: String): List<LoadedResolver> =
        _handlers.value
            .filter { isEnabled(it.resolver.id) && runCatching { it.resolver.canResolve(url) }.getOrDefault(false) }
            .sortedWith(
                compareByDescending<LoadedResolver> { priority(it.resolver.id) }
                    .thenBy { it.resolver.displayName }
            )

    /** The user's ordering weight for a handler; higher runs first. */
    fun priority(id: String): Int = settingsRepository.settings.value.slice(id).priority

    /**
     * The option descriptors a handler publishes, read defensively: a plugin that is not
     * configurable simply declares none, and one whose getter throws must not take the
     * Extensions panel down.
     */
    fun optionDescriptors(handler: LoadedResolver): List<ResolverOption> =
        (handler.resolver as? ConfigurableLinkResolver)
            ?.let { runCatching { it.optionDescriptors }.getOrDefault(emptyList()) }
            ?: emptyList()

    /** The handler's effective config: every descriptor default, overlaid with stored values. */
    fun handlerSettings(id: String): ResolverConfig {
        val descriptors = _handlers.value
            .firstOrNull { it.resolver.id == id }
            ?.let(::optionDescriptors)
            ?: emptyList()
        return ResolverConfig(settingsRepository.settings.value.slice(id).optionValues).mergedWith(descriptors)
    }

    /**
     * Run a handler's blocking multi-link extraction off the UI thread, wrapping any failure.
     *
     * A [ConfigurableLinkResolver] is handed its persisted [ResolverConfig] and the
     * subsystem context immediately before resolving, so plugin code never touches
     * storage; a plain handler takes the legacy single-link path unchanged.
     */
    suspend fun resolveAll(handler: LoadedResolver, url: String): Result<List<ResolvedLink>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val configurable = handler.resolver as? ConfigurableLinkResolver
                if (configurable != null) {
                    configurable.applySettings(handlerSettings(configurable.id))
                    configurable.resolveAll(url, ResolverContext(requestTimeout()))
                } else {
                    listOf(handler.resolver.resolve(url))
                }
            }
        }

    private fun requestTimeout(): Int =
        settingsRepository.settings.value.requestTimeoutSeconds.coerceIn(1, 300)

    /**
     * Load the bytes of the handler's icon, preferring the inline [LinkResolver.iconBase64]
     * and falling back to [LinkResolver.iconResourcePath] when the string is missing or
     * can't be decoded. Uses the resolver's own class loader for the resource lookup, so
     * a plugin jar can ship its icon alongside its classes. Returns null when the resolver
     * opts out or every source fails; the UI is expected to render a placeholder in that
     * case — a broken icon never brings down the resolver.
     */
    fun loadIconBytes(handler: LoadedResolver): ByteArray? {
        decodeBase64Icon(handler.resolver.iconBase64)?.let { return it }

        val path = handler.resolver.iconResourcePath ?: return null
        return runCatching {
            handler.resolver.javaClass.getResourceAsStream(path)?.use { it.readBytes() }
        }.onFailure { logger.warn("Failed to load icon for {}", handler.resolver.id, it) }
            .getOrNull()
    }

    /**
     * Tolerant Base64 icon decoder: strips an optional `data:...;base64,` URL prefix and
     * uses the MIME decoder, which accepts embedded line breaks and other whitespace from
     * a copy/pasted constant. Returns null (with a warn) on any failure so the caller can
     * fall back to the resource path without a nested try/catch.
     */
    private fun decodeBase64Icon(raw: String?): ByteArray? {
        if (raw.isNullOrBlank()) return null
        val body = raw.substringAfter(',', raw) // no-op when there's no `data:` prefix
        return runCatching { Base64.getMimeDecoder().decode(body) }
            .onFailure { logger.warn("Ignoring malformed iconBase64 for resolver", it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        settingsRepository.setEnabled(id, enabled)
    }

    /** Store one option value for a handler, effective on its next resolve. */
    suspend fun setOptionValue(id: String, key: String, value: String) {
        settingsRepository.setOptionValue(id, key, value)
    }

    /** Drop an option back to its descriptor default. */
    suspend fun clearOptionValue(id: String, key: String) {
        settingsRepository.clearOptionValue(id, key)
    }

    suspend fun setPriority(id: String, priority: Int) {
        settingsRepository.setPriority(id, priority)
    }

    suspend fun setAlwaysAsk(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(alwaysAskHandler = enabled) }
    }

    suspend fun setRequestTimeout(seconds: Int) {
        settingsRepository.updateSettings { it.copy(requestTimeoutSeconds = seconds.coerceIn(1, 300)) }
    }

    /** Copy [jar] into the extensions directory and reload. */
    suspend fun install(jar: Path): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = extensionDir()
            Files.createDirectories(dir)
            val target = dir.resolve(jar.fileName.toString())
            Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING)
            reload()
        }.onFailure { logger.error("Failed to install extension {}", jar, it) }
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
            }.onFailure { logger.error("Failed to remove extension {}", id, it) }
        }
    }
}
