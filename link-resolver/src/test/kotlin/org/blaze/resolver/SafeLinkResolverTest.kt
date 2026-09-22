package org.blaze.resolver

import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
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
}
