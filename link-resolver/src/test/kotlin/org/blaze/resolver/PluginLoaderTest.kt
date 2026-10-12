package org.blaze.resolver

import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverManifest
import org.blaze.resolver.core.PluginLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Public no-arg-ctor providers the loader can instantiate from the *test classpath*: the temp
 * jars list these by `mainClass` / service entry, and the jar's URLClassLoader simply finds
 * them through its parent. That keeps the loader tests focused on the manifest pipeline
 * instead of compiling classes into jars at test time.
 */
class AlphaServiceResolver : LinkResolver {
    override fun canResolve(url: String): Boolean = url.contains("alpha.example")
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = "https://direct/alpha")
}

class BetaMainResolver : LinkResolver {
    override fun canResolve(url: String): Boolean = url.contains("beta.example")
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = "https://direct/beta")
}

class GammaServiceResolver : LinkResolver {
    override fun canResolve(url: String): Boolean = true
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = "https://direct/gamma")
}

/**
 * The manifest-strict loading contract, exercised against real temp jars: no manifest (or an
 * unreadable one) means the jar is *skipped and reported*, never loaded; `mainClass` and
 * ServiceLoader discovery both bind the first provider to the jar-level manifest; and the
 * compatibility gate honors [PluginLoader]'s appVersion.
 */
class PluginLoaderTest {

    private fun tempDir(): Path = Files.createTempDirectory("blaze-plugins")

    private fun jar(
        dir: Path,
        name: String,
        manifestJson: String? = null,
        services: List<String> = emptyList(),
    ): Path {
        val jar = dir.resolve(name)
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            manifestJson?.let {
                out.putNextEntry(JarEntry(ResolverManifest.PATH))
                out.write(it.toByteArray())
                out.closeEntry()
            }
            if (services.isNotEmpty()) {
                out.putNextEntry(JarEntry("META-INF/services/${LinkResolver::class.java.name}"))
                out.write(services.joinToString("\n").toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    private fun manifestJson(
        id: String,
        mainClass: String? = null,
        extra: String = "",
    ): String = buildString {
        append("""{"id": "$id", "displayName": "$id plugin" """)
        if (mainClass != null) append(""", "mainClass": "$mainClass" """)
        if (extra.isNotEmpty()) append(", $extra")
        append("}")
    }

    @Test
    fun `a valid manifest with mainClass instantiates exactly that class`() {
        val dir = tempDir()
        jar(
            dir, "beta.jar",
            manifestJson("beta-handler", mainClass = BetaMainResolver::class.java.name),
        )

        val result = PluginLoader().load(dir)
        assertEquals(1, result.resolvers.size)
        val handler = result.resolvers.first()
        assertEquals("beta-handler", handler.id)
        assertEquals("beta-handler", handler.manifest.id)
        assertEquals(BetaMainResolver::class.java.name, handler.manifest.mainClass)
        assertTrue(handler.resolver.canResolve("https://beta.example/x"))
        assertEquals(dir.resolve("beta.jar"), handler.pluginPath)
        assertTrue(result.rejectedJars.isEmpty())
    }

    @Test
    fun `without mainClass providers are discovered through the service loader`() {
        val dir = tempDir()
        jar(
            dir, "alpha.jar",
            manifestJson("alpha-handler"),
            services = listOf(AlphaServiceResolver::class.java.name),
        )

        val result = PluginLoader().load(dir)
        assertEquals(1, result.resolvers.size)
        assertEquals("alpha-handler", result.resolvers.first().id)
        assertTrue(result.resolvers.first().resolver.canResolve("https://alpha.example/x"))
    }

    @Test
    fun `one manifest means one handler so extra service providers are skipped`() {
        val dir = tempDir()
        jar(
            dir, "two.jar",
            manifestJson("first-only"),
            services = listOf(AlphaServiceResolver::class.java.name, GammaServiceResolver::class.java.name),
        )

        val result = PluginLoader().load(dir)
        assertEquals(1, result.resolvers.size)
        assertTrue(result.resolvers.first().resolver.canResolve("https://alpha.example/x"))
    }

    @Test
    fun `a jar without any manifest is rejected with a readable reason`() {
        val dir = tempDir()
        jar(dir, "bare.jar", manifestJson = null, services = listOf(AlphaServiceResolver::class.java.name))

        val result = PluginLoader().load(dir)
        assertTrue(result.resolvers.isEmpty())
        assertEquals(1, result.rejectedJars.size)
        assertTrue(result.rejectedJars.first().reason.contains("missing", ignoreCase = true))
    }

    @Test
    fun `a present-but-invalid manifest is rejected as invalid, not missing`() {
        val dir = tempDir()
        jar(dir, "bad-json.jar", manifestJson = "{ broken")
        jar(dir, "future.jar", manifestJson = manifestJson("x", extra = """"manifestVersion": 99"""))
        jar(dir, "bad-id.jar", manifestJson = """{"id": "Bad ID", "displayName": "X"}""")

        val result = PluginLoader().load(dir)
        assertTrue(result.resolvers.isEmpty())
        assertEquals(3, result.rejectedJars.size)
        assertTrue(result.rejectedJars.all { it.reason.contains("invalid or unsupported", ignoreCase = true) })
    }

    @Test
    fun `minAppVersion gates jars the running app cannot support`() {
        val dir = tempDir()
        jar(
            dir, "newer.jar",
            manifestJson("needs-2", extra = """"minAppVersion": "2.0""""),
        )

        val rejected = PluginLoader(appVersion = "1.0.0").load(dir)
        assertTrue(rejected.resolvers.isEmpty())
        assertEquals(1, rejected.rejectedJars.size)
        assertTrue(rejected.rejectedJars.first().reason.contains(">= 2.0"))

        // A blank appVersion (test/embedded hosts) disables the gate entirely.
        assertTrue(PluginLoader().load(dir).rejectedJars.isEmpty())
    }

    @Test
    fun `untrusted jars are collected before the manifest is even read`() {
        val dir = tempDir()
        jar(
            dir, "pending.jar",
            manifestJson("p"),
            services = listOf(AlphaServiceResolver::class.java.name),
        )

        val result = PluginLoader().load(dir, trustFilter = { false })
        assertTrue(result.resolvers.isEmpty())
        assertTrue(result.rejectedJars.isEmpty())
        assertEquals(listOf(dir.resolve("pending.jar")), result.untrustedJars)
    }

    @Test
    fun `a declared mainClass that is missing or not an SPI keeps the jar out`() {
        val dir = tempDir()
        jar(dir, "ghost.jar", manifestJson("g", mainClass = "com.acme.DoesNotExist"))
        jar(dir, "wrong.jar", manifestJson("w", mainClass = "java.lang.String"))

        val result = PluginLoader().load(dir)
        assertTrue(result.resolvers.isEmpty())
        // Instantiate failures are logged and skipped; the jar isn't "rejected" metadata-wise.
        assertTrue(result.rejectedJars.isEmpty())
    }

    @Test
    fun `peekManifest reads metadata code-free and null for a manifest-less jar`() {
        val dir = tempDir()
        val good = jar(dir, "good.jar", manifestJson("peeked", extra = """"author": "Acme", "urlPatterns": ["https://*"]"""))
        val bare = jar(dir, "bare.jar", manifestJson = null)

        val loader = PluginLoader()
        val manifest = loader.peekManifest(good)
        assertNotNull(manifest)
        assertEquals("peeked", manifest.id)
        assertEquals("Acme", manifest.author)
        assertNull(loader.peekManifest(bare))
    }
}
