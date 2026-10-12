package org.blaze.resolver.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.blaze.resolver.api.ConfigurableLinkResolver
import org.blaze.resolver.api.IconFormat
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverConfig
import org.blaze.resolver.api.ResolverContext
import org.blaze.resolver.api.ResolverIcon
import org.blaze.resolver.api.ResolverManifest
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.api.mergedWith
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Central registry of link handlers: the built-ins bundled with the app (each paired with a
 * code-defined [ResolverManifest]) plus any plugin jars found in the extensions directory.
 * Handles enable/disable state, plugin install / uninstall and the actual (blocking)
 * resolve call, dispatched onto an IO thread.
 *
 * Everything the UI shows — name, description, author, icon — comes from the handler's
 * manifest, never from executing its code. Plugin jars are gated by [jarTrust] before any
 * of their code runs: an untrusted jar is skipped by [reload] and surfaced through
 * [onUntrustedJars] so the host can ask the user to consent first; jars that fail the
 * manifest or compatibility gates are reported through [rejectedJars] with a readable
 * reason. Both trust hooks default to "trust everything", keeping hosts without a consent
 * UI on simple behavior.
 */
class LinkResolverRegistry(
    private val builtIns: List<BuiltInResolver>,
    private val pluginLoader: PluginLoader,
    private val settingsRepository: LinkResolverSettingsRepository,
    private val jarTrust: (Path) -> Boolean = { true },
    private val onUntrustedJars: (List<Path>) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(LinkResolverRegistry::class.java)

    private val _handlers = MutableStateFlow<List<LoadedResolver>>(emptyList())
    /** All discovered handlers (built-in + plugin), regardless of enabled state. */
    val handlers: StateFlow<List<LoadedResolver>> = _handlers.asStateFlow()

    private val _rejectedJars = MutableStateFlow<List<PluginLoader.RejectedJar>>(emptyList())
    /** Jars sitting in the extensions directory that failed the manifest gates, with reasons. */
    val rejectedJars: StateFlow<List<PluginLoader.RejectedJar>> = _rejectedJars.asStateFlow()

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
        val result = pluginLoader.load(dir, jarTrust)
        pluginLoaders = result.classLoaders
        _rejectedJars.update { result.rejectedJars }

        val merged = buildList {
            addAll(
                builtIns.map {
                    LoadedResolver(
                        id = it.manifest.id,
                        manifest = it.manifest,
                        resolver = it.resolver,
                        source = ResolverSource.BUILTIN,
                    )
                },
            )
            addAll(result.resolvers)
        }
        _handlers.update { merged }

        // Hand the refused jars to the host: it can prompt the user and reload once they approve.
        if (result.untrustedJars.isNotEmpty()) onUntrustedJars(result.untrustedJars)
    }

    fun extensionDir(): Path =
        Path.of(settingsRepository.settings.value.extensionDir)

    fun isEnabled(id: String): Boolean =
        settingsRepository.settings.value.isEnabled(id)

    /**
     * Enabled handlers that claim [url], best-first: a user-set priority (or the manifest's
     * default when the user never touched it) orders them, display name breaks ties. The
     * "use the first one silently" path of always-ask-off therefore honors the handler the
     * user prefers.
     *
     * A manifest with `urlPatterns` is a hard pre-gate: the plugin's own [canResolve] is
     * never called for a URL none of the declared patterns match.
     */
    fun enabledHandlers(url: String): List<LoadedResolver> =
        _handlers.value
            .filter { isEnabled(it.id) && it.manifest.matches(url) }
            .filter { runCatching { it.resolver.canResolve(url) }.getOrDefault(false) }
            .sortedWith(
                compareByDescending<LoadedResolver> { priority(it) }
                    .thenBy { it.manifest.displayName }
            )

    /** The handler's effective ordering weight: stored value, else manifest default, else 0. */
    fun priority(handler: LoadedResolver): Int =
        settingsRepository.settings.value.slice(handler.id).priority
            ?: handler.manifest.defaultPriority
            ?: 0

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
            .firstOrNull { it.id == id }
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
                    configurable.applySettings(handlerSettings(handler.id))
                    configurable.resolveAll(url, ResolverContext(requestTimeout()))
                } else {
                    listOf(handler.resolver.resolve(url))
                }
            }
        }

    private fun requestTimeout(): Int =
        settingsRepository.settings.value.requestTimeoutSeconds.coerceIn(1, 300)

    /**
     * Load the icon the handler's manifest declares, preferring vector over raster and —
     * in dark mode — the declared dark variant over the light one (falling back to the
     * other mode's assets when a variant is missing). Bytes are read through the
     * resolver's own class loader, so plugin jars ship icons inside their jar and built-ins
     * inside the module. Returns null when nothing is declared or every declared path is
     * missing; the UI renders its placeholder in that case — a broken icon never takes a
     * resolver down.
     */
    fun loadIcon(handler: LoadedResolver, darkMode: Boolean = false): ResolverIcon? {
        val icons = handler.manifest.icons
        val candidates = buildList {
            if (darkMode) {
                add(icons.svgDark to IconFormat.SVG)
                add(icons.pngDark to IconFormat.PNG)
            }
            add(icons.svg to IconFormat.SVG)
            add(icons.png to IconFormat.PNG)
            if (!darkMode) {
                // An author that only shipped dark variants should still render something.
                add(icons.svgDark to IconFormat.SVG)
                add(icons.pngDark to IconFormat.PNG)
            }
        }.filter { (path, _) -> path != null }
            .map { (path, format) -> icons.normalized(path!!) to format }
            .distinctBy { it.first }

        for ((path, format) in candidates) {
            val bytes = readResource(handler, path) ?: continue
            return ResolverIcon(bytes, format)
        }
        return null
    }

    private fun readResource(handler: LoadedResolver, path: String): ByteArray? = runCatching {
        handler.resolver.javaClass.getResourceAsStream("/$path")?.use { it.readBytes() }
    }.onFailure { logger.warn("Failed to load icon {} for {}", path, handler.id, it) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

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

    /**
     * Copy [jar] into the extensions directory and reload. The jar is validated first: a
     * file without a readable manifest never reaches the user's extensions folder, so an
     * install click on garbage fails with a message instead of leaving a dead jar behind.
     */
    suspend fun install(jar: Path): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireNotNull(ResolverManifest.load(jar)) {
                "This jar has no valid ${ResolverManifest.PATH}; it cannot be installed as a link handler"
            }
            val dir = extensionDir()
            Files.createDirectories(dir)
            val target = dir.resolve(jar.fileName.toString())
            Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING)
            reload()
        }.onFailure { logger.error("Failed to install extension {}", jar, it) }
    }

    /** Delete a plugin jar and reload. Built-ins cannot be removed. */
    suspend fun uninstall(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val handler = _handlers.value.firstOrNull { it.id == id }
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
