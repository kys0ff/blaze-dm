package org.blaze.resolver

import kotlinx.coroutines.test.runTest
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.builtin.MediafireResolver
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.PluginLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeResolver(
    override val id: String,
    private val host: String,
    private val directUrl: String
) : LinkResolver {
    override val displayName: String = id
    override val description: String = "fake"
    override fun canResolve(url: String): Boolean = url.contains(host)
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = directUrl)
}

class LinkResolverRegistryTest {

    private fun tempDir() = Files.createTempDirectory("blaze-resolver")

    private fun registryWith(vararg resolvers: LinkResolver): LinkResolverRegistry {
        val dir = tempDir()
        val settings = LinkResolverSettingsRepository(dir)
        return LinkResolverRegistry(
            builtIns = resolvers.toList(),
            pluginLoader = PluginLoader(),
            settingsRepository = settings
        )
    }

    @Test
    fun `enabled handlers match by url`() {
        val registry = registryWith(
            FakeResolver("alpha", "alpha.com", "https://direct/a"),
            FakeResolver("beta", "beta.com", "https://direct/b")
        )
        val matches = registry.enabledHandlers("https://alpha.com/f/123")
        assertEquals(listOf("alpha"), matches.map { it.resolver.id })
    }

    @Test
    fun `disabling a handler removes it from candidates`() = runTest {
        val registry = registryWith(FakeResolver("alpha", "alpha.com", "https://direct/a"))
        assertTrue(registry.enabledHandlers("https://alpha.com/x").isNotEmpty())

        registry.setEnabled("alpha", false)
        assertFalse(registry.isEnabled("alpha"))
        assertTrue(registry.enabledHandlers("https://alpha.com/x").isEmpty())
    }

    @Test
    fun `resolve returns direct url`() = runTest {
        val registry = registryWith(FakeResolver("alpha", "alpha.com", "https://direct/a"))
        val handler = registry.enabledHandlers("https://alpha.com/x").first()
        val result = registry.resolve(handler, "https://alpha.com/x")
        assertEquals("https://direct/a", result.getOrThrow().directUrl)
    }

    @Test
    fun `mediafire share pages are recognised but direct links are not`() {
        val resolver = MediafireResolver()
        assertTrue(resolver.canResolve("https://www.mediafire.com/download/abc/file.zip"))
        assertTrue(resolver.canResolve("https://mediafire.com/f/abc"))
        // Already-direct file hosts and other sources must be left to the engine.
        assertFalse(resolver.canResolve("https://download123.mediafire.com/abc/file.zip"))
        assertFalse(resolver.canResolve("https://example.com/file.zip"))
        assertFalse(resolver.canResolve("magnet:?xt=urn:btih:abc"))
    }
}
