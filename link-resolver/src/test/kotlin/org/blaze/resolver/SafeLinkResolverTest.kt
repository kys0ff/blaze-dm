package org.blaze.resolver

import org.blaze.resolver.api.ConfigurableLinkResolver
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverConfig
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.core.SafeLinkResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ExplosiveResolver : LinkResolver {
    override val id: String get() = error("boom id")
    override val displayName: String get() = error("boom name")
    override val description: String get() = error("boom desc")
    override val iconResourcePath: String? get() = error("boom icon path")
    override val iconBase64: String? get() = error("boom icon b64")
    override fun canResolve(url: String): Boolean = error("boom canResolve")
    override fun resolve(url: String): ResolvedLink = error("unreachable in these tests")
}

private class GoodResolver : LinkResolver {
    override val id = "alpha"
    override val displayName = "Alpha"
    override val description = "alpha-ish"
    override val iconBase64: String? = "AAAA"
    override fun canResolve(url: String): Boolean = url.contains("alpha.com")
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = "https://direct/a")
}

/** A configurable plugin whose config surface blows up; the wrapper must neutralise it. */
private class ExplosiveConfigurableResolver : ConfigurableLinkResolver {
    override val id = "boom"
    override val displayName = "Boom"
    override val description = "boom"
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
    fun `a throwing plugin is contained across every metadata accessor`() {
        val safe = SafeLinkResolver(ExplosiveResolver(), pluginPath = null)

        assertTrue(safe.id.startsWith("invalid:"))
        assertEquals(safe.id, safe.displayName)
        assertEquals("", safe.description)
        assertNull(safe.iconResourcePath)
        assertNull(safe.iconBase64)
        // A crashing canResolve must report "no match" rather than propagate.
        assertFalse(safe.canResolve("https://anything"))
    }

    @Test
    fun `a well-behaved plugin is passed through unchanged`() {
        val safe = SafeLinkResolver(GoodResolver(), pluginPath = null)

        assertEquals("alpha", safe.id)
        assertEquals("Alpha", safe.displayName)
        assertEquals("alpha-ish", safe.description)
        assertEquals("AAAA", safe.iconBase64)
        assertTrue(safe.canResolve("https://alpha.com/x"))
        assertFalse(safe.canResolve("https://beta.com/x"))
        assertEquals("https://direct/a", safe.resolve("https://alpha.com/x").directUrl)
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
