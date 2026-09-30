package org.blaze.theming.core

import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.ConfigurableBlazeThemeProvider
import org.blaze.theming.api.ThemeAppearanceMode
import org.blaze.theming.api.ThemeConfig
import org.blaze.theming.api.ThemeConfigDefaults
import org.blaze.theming.api.ThemeOption
import org.blaze.theming.api.ThemePalette
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Sandboxing wrapper around a third-party [BlazeThemeProvider].
 *
 * A theme jar is untrusted code: any of its members can throw — an [Exception] from a logic
 * bug, or even an [Error] such as `NoClassDefFoundError` from a version mismatch — and the
 * active palette is resolved on the UI thread while the app renders, so a single throwing
 * [provide] would otherwise take the whole window down. Every call into the delegate is
 * funnelled through [guarded], which catches [Throwable], logs once, and returns a harmless
 * fallback so the rest of the app (and the other themes) keep working. Plugin palettes are
 * additionally run through [sanitized] so an unrenderable value (out-of-range long, missing
 * alpha byte) degrades to "token unset" instead of painting garbage.
 *
 * The broken theme is still listed in Settings — under a stable synthetic id that never
 * touches the plugin — so the user can inspect or remove it; it simply contributes nothing
 * usable. Built-in themes are trusted and are deliberately left unwrapped.
 */
internal open class SafeThemeProvider(
    protected val delegate: BlazeThemeProvider,
    pluginPath: Path?,
) : BlazeThemeProvider {
    private val logger = LoggerFactory.getLogger(SafeThemeProvider::class.java)

    /** Identity that doesn't invoke plugin code, used when the real [id] can't be read. */
    private val fallbackId =
        "invalid:" + (pluginPath?.fileName?.toString() ?: delegate.javaClass.name)

    protected fun <T> guarded(what: String, fallback: T, call: () -> T): T =
        runCatching(call).getOrElse {
            logger.error("Theme plugin {} failed to {}", delegate.javaClass.name, what, it)
            fallback
        }

    override val id: String by lazy { guarded("read id", fallbackId) { delegate.id } }

    override val displayName: String by lazy {
        guarded("read displayName", id) { delegate.displayName }
    }

    override val description: String by lazy {
        guarded("read description", "") { delegate.description }
    }

    override val author: String by lazy { guarded("read author", "") { delegate.author } }

    override val version: String by lazy { guarded("read version", "") { delegate.version } }

    override val supportedModes: Set<ThemeAppearanceMode> by lazy {
        guarded("read supportedModes", ThemeAppearanceMode.ALL) {
            // An empty (or null-poisoned) declaration would make the theme unselectable in
            // every mode; treat it as "supports both", the contract's own default.
            delegate.supportedModes.takeIf { it.isNotEmpty() } ?: ThemeAppearanceMode.ALL
        }
    }

    override fun provide(dark: Boolean): ThemePalette =
        guarded("provide(dark=$dark)", ThemePalette.Empty) { delegate.provide(dark).sanitized() }

    /**
     * Wrap [provider] for use as a plugin, preserving the [ConfigurableBlazeThemeProvider] facet
     * when the jar declares one so Settings can still offer its options form.
     */
    companion object {
        fun wrap(provider: BlazeThemeProvider, pluginPath: Path): BlazeThemeProvider =
            if (provider is ConfigurableBlazeThemeProvider) {
                SafeConfigurableThemeProvider(provider, pluginPath)
            } else {
                SafeThemeProvider(provider, pluginPath)
            }
    }
}

/**
 * [SafeThemeProvider] for a plugin that also implements [ConfigurableBlazeThemeProvider].
 *
 * The extra facet is guarded with the same rules: descriptors fall back to an empty list (the
 * Options button simply disappears), and a throwing `applySettings`/`settings` cannot stop the
 * palette from resolving.
 */
internal class SafeConfigurableThemeProvider(
    configurableDelegate: ConfigurableBlazeThemeProvider,
    pluginPath: Path?,
) : SafeThemeProvider(configurableDelegate, pluginPath), ConfigurableBlazeThemeProvider {

    private val configDelegate get() = delegate as ConfigurableBlazeThemeProvider

    override val optionDescriptors: List<ThemeOption> by lazy {
        guarded("read optionDescriptors", emptyList()) { configDelegate.optionDescriptors }
    }

    override fun applySettings(settings: ThemeConfig) {
        guarded("applySettings", Unit) { configDelegate.applySettings(settings) }
    }

    override fun settings(): ThemeConfig =
        guarded("settings", ThemeConfigDefaults.fromDescriptors(optionDescriptors)) {
            configDelegate.settings()
        }
}
