package org.blaze.resolver

import kotlinx.coroutines.test.runTest
import org.blaze.resolver.api.ConfigurableLinkResolver
import org.blaze.resolver.api.IconFormat
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverConfig
import org.blaze.resolver.api.ResolverContext
import org.blaze.resolver.api.ResolverIconSpec
import org.blaze.resolver.api.ResolverManifest
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.api.textOption
import org.blaze.resolver.builtin.mediafire.MediafireResolver
import org.blaze.resolver.core.BuiltInResolver
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.PluginLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A behavior-only fake; everything the registry sees about it comes from its manifest. */
private class FakeResolver(
    private val host: String,
    private val directUrl: String,
) : LinkResolver {
    var queries = 0
        private set

    override fun canResolve(url: String): Boolean {
        queries++
        return url.contains(host)
    }

    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = directUrl)
}

/**
 * A configurable handler that remembers what the registry handed it ([appliedConfig] /
 * [appliedContext]) and emits a whole list of links, so the multi-link and settings plumbing can
 * be asserted without any network code.
 */
private class FakeConfigurableResolver(
    private val host: String,
    private val links: List<String>,
    override val optionDescriptors: List<ResolverOption> =
        listOf(textOption("token", "Token", default = "fallback")),
) : ConfigurableLinkResolver {
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

private fun testManifest(
    id: String,
    name: String = id.replaceFirstChar { it.uppercase() },
    patterns: List<String> = emptyList(),
    defaultPriority: Int? = null,
    icons: ResolverIconSpec = ResolverIconSpec(),
): ResolverManifest = ResolverManifest(
    id = id,
    displayName = name,
    description = "fake-$id",
    urlPatterns = patterns,
    defaultPriority = defaultPriority,
    icons = icons,
)

class LinkResolverRegistryTest {

    private fun tempDir(): Path = Files.createTempDirectory("blaze-resolver")

    private fun registryWith(vararg builtIns: BuiltInResolver): LinkResolverRegistry =
        LinkResolverRegistry(
            builtIns = builtIns.toList(),
            pluginLoader = PluginLoader(),
            settingsRepository = LinkResolverSettingsRepository(tempDir()),
        )

    @Test
    fun `enabled handlers match by url`() = runTest {
        val alpha = FakeResolver("alpha.com", "https://direct/a")
        val beta = FakeResolver("beta.com", "https://direct/b")
        val registry = registryWith(
            BuiltInResolver(alpha, testManifest("alpha")),
            BuiltInResolver(beta, testManifest("beta")),
        )
        val matches = registry.enabledHandlers("https://alpha.com/f/123")
        assertEquals(listOf("alpha"), matches.map { it.id })
    }

    @Test
    fun `manifest url patterns pre-gate the plugin's own canResolve`() = runTest {
        val alpha = FakeResolver("alpha.com", "https://direct/a")
        val registry = registryWith(
            BuiltInResolver(alpha, testManifest("alpha", patterns = listOf("https://*.alpha.com/*"))),
        )

        // A URL none of the declared patterns match never reaches the (untrusted) plugin code.
        assertTrue(registry.enabledHandlers("https://other.com/f/1").isEmpty())
        assertEquals(0, alpha.queries)

        // The declared form does match, and then canResolve gets its turn.
        assertEquals(listOf("alpha"), registry.enabledHandlers("https://x.alpha.com/f/1").map { it.id })
        assertTrue(alpha.queries > 0)
    }

    @Test
    fun `disabling a handler removes it from candidates`() = runTest {
        val registry = registryWith(
            BuiltInResolver(FakeResolver("alpha.com", "https://direct/a"), testManifest("alpha")),
        )
        assertTrue(registry.enabledHandlers("https://alpha.com/x").isNotEmpty())

        registry.setEnabled("alpha", false)
        assertFalse(registry.isEnabled("alpha"))
        assertTrue(registry.enabledHandlers("https://alpha.com/x").isEmpty())
    }

    @Test
    fun `resolveAll returns the single direct url for a plain handler`() = runTest {
        val registry = registryWith(
            BuiltInResolver(FakeResolver("alpha.com", "https://direct/a"), testManifest("alpha")),
        )
        val handler = registry.enabledHandlers("https://alpha.com/x").first()
        val result = registry.resolveAll(handler, "https://alpha.com/x")
        assertEquals(listOf("https://direct/a"), result.getOrThrow().map { it.directUrl })
    }

    @Test
    fun `resolveAll returns every link from a configurable handler`() = runTest {
        val registry = registryWith(
            BuiltInResolver(
                FakeConfigurableResolver("multi.com", listOf("https://direct/1", "https://direct/2", "https://direct/3")),
                testManifest("multi"),
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
        val resolver = FakeConfigurableResolver("cfg.com", listOf("https://direct/a"))
        val registry = registryWith(BuiltInResolver(resolver, testManifest("cfg")))
        registry.setOptionValue("cfg", "token", "secret")

        val handler = registry.enabledHandlers("https://cfg.com/x").first()
        registry.resolveAll(handler, "https://cfg.com/x")

        // The stored value wins over the descriptor default; untouched keys keep their default.
        assertEquals("secret", resolver.appliedConfig?.value("token", "wrong"))
    }

    @Test
    fun `unconfigured option descriptors reach the handler as their declared default`() = runTest {
        val resolver = FakeConfigurableResolver("cfg.com", listOf("https://direct/a"))
        val registry = registryWith(BuiltInResolver(resolver, testManifest("cfg")))

        val handler = registry.enabledHandlers("https://cfg.com/x").first()
        registry.resolveAll(handler, "https://cfg.com/x")

        assertEquals("fallback", resolver.appliedConfig?.value("token", "wrong"))
    }

    @Test
    fun `resolveAll passes the subsystem request timeout to the handler`() = runTest {
        val resolver = FakeConfigurableResolver("cfg.com", listOf("https://direct/a"))
        val registry = registryWith(BuiltInResolver(resolver, testManifest("cfg")))
        registry.setRequestTimeout(42)

        val handler = registry.enabledHandlers("https://cfg.com/x").first()
        registry.resolveAll(handler, "https://cfg.com/x")

        assertEquals(42, resolver.appliedContext?.requestTimeoutSeconds)
    }

    @Test
    fun `enabled handlers are ordered by priority descending then name`() = runTest {
        val registry = registryWith(
            BuiltInResolver(FakeResolver("x.com", "https://direct/a"), testManifest("a-low")),
            BuiltInResolver(FakeResolver("x.com", "https://direct/b"), testManifest("b-high")),
            BuiltInResolver(FakeResolver("x.com", "https://direct/c"), testManifest("c-mid")),
        )
        registry.setPriority("b-high", 10)
        registry.setPriority("c-mid", 5)

        val order = registry.enabledHandlers("https://x.com/p").map { it.id }
        assertEquals(listOf("b-high", "c-mid", "a-low"), order)
    }

    @Test
    fun `the manifest default priority applies until the user overrides it`() = runTest {
        val preferred = BuiltInResolver(
            FakeResolver("x.com", "https://direct/p"),
            testManifest("preferred", defaultPriority = 7),
        )
        val plain = BuiltInResolver(FakeResolver("x.com", "https://direct/q"), testManifest("plain"))
        val registry = registryWith(preferred, plain)

        val preferredHandler = registry.handlers.value.first { it.id == "preferred" }
        assertEquals(7, registry.priority(preferredHandler))

        // An explicit user weight - even a demoting one - beats the manifest default.
        registry.setPriority("preferred", -1)
        assertEquals(-1, registry.priority(preferredHandler))
        assertEquals(0, registry.priority(registry.handlers.value.first { it.id == "plain" }))
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

    @Test
    fun `the mediafire built-in registers under its code manifest and finds its bundled png`() = runTest {
        val registry = registryWith(BuiltInResolver(MediafireResolver(), MediafireResolver.Manifest))

        val handler = registry.enabledHandlers("https://www.mediafire.com/download/abc/file.zip").first()
        assertEquals("mediafire", handler.id)
        assertEquals("MediaFire", handler.manifest.displayName)

        // The icon now rides the same manifest-resource pipeline as any plugin's.
        val icon = registry.loadIcon(handler)
        assertNotNull(icon)
        assertEquals(IconFormat.PNG, icon.format)
        assertTrue(icon.bytes.size > 1_000)
    }

    // ── Icon loading ─────────────────────────────────────────────────────────

    private fun iconRegistry(icons: ResolverIconSpec): LinkResolverRegistry = registryWith(
        BuiltInResolver(FakeResolver("alpha.com", "https://direct/a"), testManifest("alpha", icons = icons)),
    )

    private fun handlerOf(registry: LinkResolverRegistry) =
        registry.enabledHandlers("https://alpha.com/x").first()

    @Test
    fun `a declared png path is read from the handler's own resources`() {
        val registry = iconRegistry(ResolverIconSpec(png = "icons/alpha.png"))
        val icon = registry.loadIcon(handlerOf(registry))
        assertNotNull(icon)
        assertEquals(IconFormat.PNG, icon.format)
        assertContentEquals(
            javaClass.getResourceAsStream("/icons/alpha.png").readBytes(),
            icon.bytes,
        )
    }

    @Test
    fun `svg is preferred over png`() {
        val registry = iconRegistry(
            ResolverIconSpec(png = "icons/alpha.png", svg = "icons/alpha.svg")
        )
        val icon = registry.loadIcon(handlerOf(registry))
        assertEquals(IconFormat.SVG, icon?.format)
        assertContentEquals(
            javaClass.getResourceAsStream("/icons/alpha.svg").readBytes(),
            icon?.bytes,
        )
    }

    @Test
    fun `dark mode prefers the declared dark variant, light mode the light one`() {
        val registry = iconRegistry(
            ResolverIconSpec(png = "icons/alpha.png", svgDark = "icons/alpha-dark.svg")
        )
        val handler = handlerOf(registry)

        val dark = registry.loadIcon(handler, darkMode = true)
        assertEquals(IconFormat.SVG, dark?.format)
        assertContentEquals(javaClass.getResourceAsStream("/icons/alpha-dark.svg").readBytes(), dark?.bytes)

        val light = registry.loadIcon(handler, darkMode = false)
        assertEquals(IconFormat.PNG, light?.format)
    }

    @Test
    fun `a dark-only manifest still renders in light mode`() {
        val registry = iconRegistry(ResolverIconSpec(svgDark = "icons/alpha-dark.svg"))
        val icon = registry.loadIcon(handlerOf(registry), darkMode = false)
        assertEquals(IconFormat.SVG, icon?.format)
    }

    @Test
    fun `a declared-but-missing resource and an empty spec both yield null`() {
        val registry = iconRegistry(ResolverIconSpec(png = "icons/nope.png"))
        assertNull(registry.loadIcon(handlerOf(registry)))

        val bare = iconRegistry(ResolverIconSpec())
        assertNull(bare.loadIcon(handlerOf(bare)))
    }
}
