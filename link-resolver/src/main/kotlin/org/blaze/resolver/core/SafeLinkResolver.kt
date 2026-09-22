package org.blaze.resolver.core

import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Sandboxing wrapper around a third-party [LinkResolver].
 *
 * A handler jar is untrusted code: any of its members can throw — an [Exception] from a
 * logic bug, or even an [Error] such as `NoClassDefFoundError` from a version mismatch.
 * The metadata getters (`id`, `displayName`, `description`, the icon properties) and
 * [canResolve] are read on the UI thread while the picker and Settings list render, so a
 * throwing member there would otherwise take the window down. Every such call is funnelled
 * through [guarded], which catches [Throwable], logs, and returns a harmless fallback.
 *
 * [resolve] is deliberately delegated as-is: the registry already invokes it inside a
 * `runCatching` on an IO dispatcher and surfaces the plugin's own message as a readable,
 * user-facing error rather than a crash, so re-wrapping it here would only mask that text.
 *
 * Built-in handlers are trusted and are deliberately left unwrapped.
 */
internal class SafeLinkResolver(
    private val delegate: LinkResolver,
    pluginPath: Path?,
) : LinkResolver {
    private val logger = LoggerFactory.getLogger(SafeLinkResolver::class.java)

    /** Identity that doesn't invoke plugin code, used when the real [id] can't be read. */
    private val fallbackId =
        "invalid:" + (pluginPath?.fileName?.toString() ?: delegate.javaClass.name)

    private fun <T> guarded(what: String, fallback: T, call: () -> T): T =
        runCatching(call).getOrElse {
            logger.error("Link handler plugin {} failed to {}", delegate.javaClass.name, what, it)
            fallback
        }

    override val id: String by lazy { guarded("read id", fallbackId) { delegate.id } }

    override val displayName: String by lazy {
        guarded("read displayName", id) { delegate.displayName }
    }

    override val description: String by lazy {
        guarded("read description", "") { delegate.description }
    }

    override val iconResourcePath: String? by lazy {
        guarded("read iconResourcePath", null) { delegate.iconResourcePath }
    }

    override val iconBase64: String? by lazy {
        guarded("read iconBase64", null) { delegate.iconBase64 }
    }

    override fun canResolve(url: String): Boolean =
        guarded("check canResolve($url)", false) { delegate.canResolve(url) }

    override fun resolve(url: String): ResolvedLink = delegate.resolve(url)
}
