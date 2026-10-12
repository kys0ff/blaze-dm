package org.blaze.resolver

import org.blaze.resolver.api.ConfigurableLinkResolver
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverConfig
import org.blaze.resolver.api.ResolverContext
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.core.SafeLinkResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Metadata accessors are gone from the SPI (identity lives in the manifest now), so the
 * sandbox only has to neutralise the behavioral surface: [canResolve] and the options.
 */
private class ExplosiveResolver : LinkResolver {
    override fun canResolve(url: String): Boolean = error("boom canResolve")
    override fun resolve(url: String): ResolvedLink = error("unreachable in these tests")
}

private class GoodResolver : LinkResolver {
    override fun canResolve(url: String): Boolean = url.contains("alpha.com")
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = "https://direct/a")
}

/** A configurable plugin whose config surface blows up; the wrapper must neutralise it. */
private class ExplosiveConfigurableResolver : ConfigurableLinkResolver {
    override fun canResolve(url: String): Boolean = true
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = "https://direct/b")
    override val optionDescriptors: List<ResolverOption> get() = error("boom descriptors")
    override fun settings(): ResolverConfig = error("boom settings")
    override fun applySettings(settings: ResolverConfig) {
        error("boom apply")
    }
}

class SafeLinkResolverTest {

    @Test
    fun `a throwing plugin is contained on the UI-thread read paths`() {
        val safe = SafeLinkResolver(ExplosiveResolver(), pluginPath = null)

        // A crashing canResolve must report "no match" rather than propagate.
        assertFalse(safe.canResolve("https://anything"))
        assertTrue(safe.optionDescriptors.isEmpty())
        assertEquals(0, safe.settings().values.size)
    }

    @Test
    fun `a well-behaved plugin is passed through unchanged`() {
        val safe = SafeLinkResolver(GoodResolver(), pluginPath = null)

        assertTrue(safe.canResolve("https://alpha.com/x"))
        assertFalse(safe.canResolve("https://beta.com/x"))
        assertEquals("https://direct/a", safe.resolve("https://alpha.com/x").directUrl)
        // The plain single-link path is exposed as a one-element multi-link list.
        val links = safe.resolveAll("https://alpha.com/x", ResolverContext(30))
        assertEquals(listOf("https://direct/a"), links.map { it.directUrl })
    }

    @Test
    fun `resolve and resolveAll are delegated as-is so the registry surfaces the plugin message`() {
        val safe = SafeLinkResolver(ExplosiveResolver(), pluginPath = null)
        // The wrapper must NOT swallow these: the registry wraps them in runCatching and shows
        // the plugin's own readable message as a user-facing error.
        assertFailsWith<IllegalStateException> { safe.resolve("https://alpha.com/x") }
        assertFailsWith<IllegalStateException> { safe.resolveAll("https://alpha.com/x", ResolverContext(30)) }
    }

    @Test
    fun `a configurable plugin whose descriptors throw degrade to an empty list`() {
        val safe = SafeLinkResolver(ExplosiveConfigurableResolver(), pluginPath = null)
        // optionDescriptors/settings are read on the UI thread; a throwing getter must not escape.
        assertTrue(safe.optionDescriptors.isEmpty())
        assertEquals(0, safe.settings().values.size)
    }

    @Test
    fun `a throwing applySettings is swallowed rather than crashing the resolver`() {
        val safe = SafeLinkResolver(ExplosiveConfigurableResolver(), pluginPath = null)
        // Must not throw even though the delegate's applySettings always errors.
        safe.applySettings(ResolverConfig())
    }

    @Test
    fun `a plain plugin is not advertised as configurable`() {
        val safe = SafeLinkResolver(GoodResolver(), pluginPath = null)
        assertTrue(safe.optionDescriptors.isEmpty())
    }
}
