package org.blaze.resolver.api

/**
 * The optional facet of a [LinkResolver] that carries its own user-editable configuration
 * and can extract *several* links from one page.
 *
 * A plain [LinkResolver] keeps working untouched: the registry only talks to this
 * interface when the handler actually implements it, and every member has a default that
 * reproduces the simple single-link behavior. Plugins adopt it incrementally — declaring
 * [optionDescriptors] alone, or overriding [resolveAll] alone, or both.
 *
 * As with [LinkResolver], every method here is invoked on an IO thread by the registry
 * and must be blocking-safe and coroutine-free.
 */
interface ConfigurableLinkResolver : LinkResolver {

    /**
     * The options the user may edit for this handler in the Extensions panel and in
     * Settings. Descriptors are static: the app renders the form from them and stores
     * the answers, re-applying them through [applySettings] before each resolve.
     */
    val optionDescriptors: List<ResolverOption> get() = emptyList()

    /** The configuration currently in effect. Must mirror the last [applySettings]. */
    fun settings(): ResolverConfig = ResolverSettingsDefaults.fromDescriptors(optionDescriptors)

    /**
     * Adopt [settings] — the values the app merged from descriptor defaults and what the
     * user persisted. Called on the registry's IO dispatcher immediately before every
     * [resolveAll], so a handler never has to read storage itself.
     */
    fun applySettings(settings: ResolverConfig) {}

    /**
     * Extract every downloadable link [url] offers. The default adapts the single-link
     * [resolve], so existing handlers return exactly one entry unchanged.
     *
     * Return an empty list when the page holds no downloadable link; throw with a
     * readable message when the page itself could not be processed — the caller surfaces
     * both as user-facing outcomes, not crashes.
     */
    fun resolveAll(url: String, context: ResolverContext): List<ResolvedLink> =
        listOf(resolve(url))
}

/** Helpers shared by the default members and by the registry's merge step. */
object ResolverSettingsDefaults {
    /** A config holding every descriptor's default value — the "never touched" state. */
    fun fromDescriptors(descriptors: List<ResolverOption>): ResolverConfig =
        ResolverConfig(descriptors.associate { it.key to it.defaultValue })
}

/** Applies [ResolverConfig] semantics on top of descriptors; used by the registry. */
fun ResolverConfig.mergedWith(descriptors: List<ResolverOption>): ResolverConfig {
    val defaults = descriptors.associate { it.key to it.defaultValue }
    return ResolverConfig(defaults + values)
}
