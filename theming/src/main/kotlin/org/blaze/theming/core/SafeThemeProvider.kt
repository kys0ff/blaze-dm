package org.blaze.theming.core

import org.blaze.theming.api.BlazeThemeProvider
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
 * fallback so the rest of the app (and the other themes) keep working.
 *
 * The broken theme is still listed in Settings — under a stable synthetic id that never
 * touches the plugin — so the user can inspect or remove it; it simply contributes nothing
 * usable. Built-in themes are trusted and are deliberately left unwrapped.
 */
internal class SafeThemeProvider(
    private val delegate: BlazeThemeProvider,
    pluginPath: Path?,
) : BlazeThemeProvider {
    private val logger = LoggerFactory.getLogger(SafeThemeProvider::class.java)

    /** Identity that doesn't invoke plugin code, used when the real [id] can't be read. */
    private val fallbackId =
        "invalid:" + (pluginPath?.fileName?.toString() ?: delegate.javaClass.name)

    private fun <T> guarded(what: String, fallback: T, call: () -> T): T =
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

    override fun provide(dark: Boolean): ThemePalette =
        guarded("provide(dark=$dark)", ThemePalette.Empty) { delegate.provide(dark) }
}
