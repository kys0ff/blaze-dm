package org.blaze.resolver.core

import org.blaze.resolver.api.ConfigurableLinkResolver
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverConfig
import org.blaze.resolver.api.ResolverContext
import org.blaze.resolver.api.ResolverOption
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Sandboxing wrapper around a third-party [LinkResolver].
 *
 * A handler jar is untrusted code: any of its members can throw — an [Exception] from a
 * logic bug, or even an [Error] such as `NoClassDefFoundError` from a version mismatch.
 * [canResolve] and the configuration surface ([optionDescriptors], [settings],
 * [applySettings]) are read on the UI thread while the picker, the Settings list and the
 * Extensions panel render, so a throwing member there would otherwise take the window
 * down. Every such call is funnelled through [guarded], which catches [Throwable], logs,
 * and returns a harmless fallback. Identity and metadata are no longer read here at all —
 * they live in the jar's [org.blaze.resolver.api.ResolverManifest], parsed without
 * executing plugin code.
 *
 * [resolve] and [resolveAll] are deliberately delegated as-is: the registry already invokes
 * them inside a `runCatching` on an IO dispatcher and surfaces the plugin's own message as
 * a readable, user-facing error rather than a crash, so re-wrapping them here would only
 * mask that text.
 *
 * The wrapper advertises itself as [ConfigurableLinkResolver] only when the delegate is
 * one; a plain handler stays a plain handler so the registry takes the single-link path for
 * it. Built-in handlers are trusted and are deliberately left unwrapped.
 */
internal class SafeLinkResolver(
    private val delegate: LinkResolver,
    private val pluginPath: Path? = null,
) : ConfigurableLinkResolver {
    private val logger = LoggerFactory.getLogger(SafeLinkResolver::class.java)

    /** True when the wrapped plugin opted into the configurable/multi-link facet. */
    private val delegateConfigurable: Boolean get() = delegate is ConfigurableLinkResolver

    private fun <T> guarded(what: String, fallback: T, call: () -> T): T =
        runCatching(call).getOrElse {
            logger.error(
                "Link handler plugin {} ({}) failed to {}",
                delegate.javaClass.name, pluginPath?.fileName ?: "unknown jar", what, it,
            )
            fallback
        }

    override fun canResolve(url: String): Boolean =
        guarded("check canResolve($url)", false) { delegate.canResolve(url) }

    override val optionDescriptors: List<ResolverOption> by lazy {
        if (!delegateConfigurable) emptyList()
        else guarded("read optionDescriptors", emptyList()) {
            (delegate as ConfigurableLinkResolver).optionDescriptors
        }
    }

    override fun settings(): ResolverConfig =
        if (!delegateConfigurable) ResolverConfig()
        else guarded("read settings", ResolverConfig()) {
            (delegate as ConfigurableLinkResolver).settings()
        }

    override fun applySettings(settings: ResolverConfig) {
        if (delegateConfigurable) {
            guarded("apply settings", Unit) {
                (delegate as ConfigurableLinkResolver).applySettings(settings)
            }
        }
    }

    override fun resolve(url: String): ResolvedLink = delegate.resolve(url)

    override fun resolveAll(url: String, context: ResolverContext): List<ResolvedLink> =
        if (delegateConfigurable) {
            (delegate as ConfigurableLinkResolver).resolveAll(url, context)
        } else {
            listOf(delegate.resolve(url))
        }
}
