package org.blaze.resolver

import kotlinx.coroutines.test.runTest
import org.blaze.resolver.api.ConfigurableLinkResolver
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverConfig
import org.blaze.resolver.api.ResolverContext
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.api.textOption
import org.blaze.resolver.builtin.mediafire.MediafireResolver
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.PluginLoader
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeResolver(
    override val id: String,
    private val host: String,
    private val directUrl: String,
    override val iconResourcePath: String? = null,
    override val iconBase64: String? = null
) : LinkResolver {
    override val displayName: String = id
    override val description: String = "fake"
    override fun canResolve(url: String): Boolean = url.contains(host)
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = directUrl)
}

/**
 * A configurable handler that remembers what the registry handed it ([appliedConfig] /
 * [appliedContext]) and emits a whole list of links, so the multi-link and settings plumbing can
 * be asserted without any network code.
 */
private class FakeConfigurableResolver(
    override val id: String,
    private val host: String,
    private val links: List<String>,
    override val optionDescriptors: List<ResolverOption> =
        listOf(textOption("token", "Token", default = "fallback")),
) : ConfigurableLinkResolver {
    override val displayName: String = id
    override val description: String = "fake-configurable"
    var appliedConfig: ResolverConfig? = null
    var appliedContext: ResolverContext? = null

    override fun canResolve(url: String): Boolean = url.contains(host)
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = links.first())
    override fun applySettings(settings: ResolverConfig) {
        appliedConfig = settings
    }

    override fun resolveAll(url: String, context: ResolverContext): List<ResolvedLink> {
        appliedContext = context
        return links.map { ResolvedLink(directUrl = it) }
    }
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
    fun `resolveAll returns the single direct url for a plain handler`() = runTest {
        val registry = registryWith(FakeResolver("alpha", "alpha.com", "https://direct/a"))
        val handler = registry.enabledHandlers("https://alpha.com/x").first()
        val result = registry.resolveAll(handler, "https://alpha.com/x")
        assertEquals(listOf("https://direct/a"), result.getOrThrow().map { it.directUrl })
    }

    @Test
    fun `resolveAll returns every link from a configurable handler`() = runTest {
        val registry = registryWith(
            FakeConfigurableResolver(
                "multi", "multi.com",
                links = listOf("https://direct/1", "https://direct/2", "https://direct/3")
            )
        )
        val handler = registry.enabledHandlers("https://multi.com/x").first()
        val links = registry.resolveAll(handler, "https://multi.com/x").getOrThrow()
        assertEquals(
            listOf("https://direct/1", "https://direct/2", "https://direct/3"),
            links.map { it.directUrl }
        )
    }

    @Test
    fun `resolveAll hands the handler its persisted option value over the default`() = runTest {
        val resolver = FakeConfigurableResolver("cfg", "cfg.com", links = listOf("https://direct/a"))
        val registry = registryWith(resolver)
        registry.setOptionValue("cfg", "token", "secret")

        val handler = registry.enabledHandlers("https://cfg.com/x").first()
        registry.resolveAll(handler, "https://cfg.com/x")

        // The stored value wins over the descriptor default; untouched keys keep their default.
        assertEquals("secret", resolver.appliedConfig?.value("token", "wrong"))
    }

    @Test
    fun `unconfigured option descriptors reach the handler as their declared default`() = runTest {
        val resolver = FakeConfigurableResolver("cfg", "cfg.com", links = listOf("https://direct/a"))
        val registry = registryWith(resolver)

        val handler = registry.enabledHandlers("https://cfg.com/x").first()
        registry.resolveAll(handler, "https://cfg.com/x")

        assertEquals("fallback", resolver.appliedConfig?.value("token", "wrong"))
    }

    @Test
    fun `resolveAll passes the subsystem request timeout to the handler`() = runTest {
        val resolver = FakeConfigurableResolver("cfg", "cfg.com", links = listOf("https://direct/a"))
        val registry = registryWith(resolver)
        registry.setRequestTimeout(42)

        val handler = registry.enabledHandlers("https://cfg.com/x").first()
        registry.resolveAll(handler, "https://cfg.com/x")

        assertEquals(42, resolver.appliedContext?.requestTimeoutSeconds)
    }

    @Test
    fun `enabled handlers are ordered by priority descending then name`() = runTest {
        val registry = registryWith(
            FakeResolver("a-low", "x.com", "https://direct/a"),
            FakeResolver("b-high", "x.com", "https://direct/b"),
            FakeResolver("c-mid", "x.com", "https://direct/c"),
        )
        registry.setPriority("b-high", 10)
        registry.setPriority("c-mid", 5)

        val order = registry.enabledHandlers("https://x.com/p").map { it.resolver.id }
        assertEquals(listOf("b-high", "c-mid", "a-low"), order)
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

    // ── Icon loading ─────────────────────────────────────────────────────────

    @Test
    fun `iconBase64 is decoded and returned as bytes`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val encoded = Base64.getEncoder().encodeToString(payload)
        val registry = registryWith(
            FakeResolver("alpha", "alpha.com", "https://direct/a", iconBase64 = encoded)
        )
        val handler = registry.enabledHandlers("https://alpha.com/x").first()
        assertContentEquals(payload, registry.loadIconBytes(handler))
    }

    @Test
    fun `data URL prefix on iconBase64 is stripped before decoding`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val dataUrl = "data:image/png;base64,${Base64.getEncoder().encodeToString(payload)}"
        val registry = registryWith(
            FakeResolver("alpha", "alpha.com", "https://direct/a", iconBase64 = dataUrl)
        )
        val handler = registry.enabledHandlers("https://alpha.com/x").first()
        assertContentEquals(payload, registry.loadIconBytes(handler))
    }

    @Test
    fun `malformed iconBase64 falls back to iconResourcePath without crashing`() {
        val registry = registryWith(
            FakeResolver(
                id = "alpha",
                host = "alpha.com",
                directUrl = "https://direct/a",
                // Something that doesn't exist on the classpath: fallback also fails,
                // but the important thing is that a broken base64 never throws. MIME
                // ignores characters outside the base64 alphabet, so @@@@ decodes to
                // nothing and the registry treats it as "no icon".
                iconResourcePath = "/does-not-exist.png",
                iconBase64 = "@@@@"
            )
        )
        val handler = registry.enabledHandlers("https://alpha.com/x").first()
        assertNull(registry.loadIconBytes(handler))
    }

    @Test
    fun `handler without any icon source returns null`() {
        val registry = registryWith(FakeResolver("alpha", "alpha.com", "https://direct/a"))
        val handler = registry.enabledHandlers("https://alpha.com/x").first()
        assertNull(registry.loadIconBytes(handler))
    }
}
