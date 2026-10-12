package org.blaze.resolver

import org.blaze.resolver.api.ResolverManifest
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest is the single descriptor of a handler, so its parsing rules are the plugin
 * contract: lenient about unknown keys, ruthless about anything that would make a jar
 * unidentifiable or unsafe (future schema, broken ids, path traversal in icon resources).
 */
class ResolverManifestTest {

    private fun parse(text: String?) = ResolverManifest.parse(text)

    @Test
    fun `a full manifest parses with every field projected`() {
        val manifest = parse(
            """
            {
              "manifestVersion": 1,
              "id": "acme-host",
              "displayName": "Acme Host",
              "description": "Resolves Acme share pages",
              "version": "2.1.0",
              "author": "Acme",
              "homepage": "https://acme.example",
              "license": "MIT",
              "issuesUrl": "https://acme.example/issues",
              "mainClass": "com.acme.AcmeResolver",
              "icons": { "png": "icons/acme.png", "svg": "icons/acme.svg", "svgDark": "icons/acme-dark.svg" },
              "urlPatterns": ["https://*.acme.example/*"],
              "defaultPriority": 5,
              "minAppVersion": "1.2.0"
            }
            """.trimIndent()
        )
        assertNotNull(manifest)
        assertEquals("acme-host", manifest.id)
        assertEquals("Acme Host", manifest.displayName)
        assertEquals("com.acme.AcmeResolver", manifest.mainClass)
        assertEquals("icons/acme.svg", manifest.icons.svg)
        assertEquals("icons/acme-dark.svg", manifest.icons.svgDark)
        assertEquals(listOf("https://*.acme.example/*"), manifest.urlPatterns)
        assertEquals(5, manifest.defaultPriority)
        assertEquals("1.2.0", manifest.minAppVersion)
    }

    @Test
    fun `defaults fill an id-and-name-only manifest and unknown keys are ignored`() {
        val manifest = parse("""{"id": "x1", "displayName": "X", "fashionKey": 42}""")
        assertNotNull(manifest)
        assertEquals(1, manifest.manifestVersion)
        assertEquals("0.0.0", manifest.version)
        assertEquals("", manifest.author)
        assertNull(manifest.mainClass)
        assertTrue(manifest.urlPatterns.isEmpty())
        assertNull(manifest.defaultPriority)
    }

    @Test
    fun `blank, malformed and JSON-invalid content never yields a manifest`() {
        assertNull(parse(null))
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("{ not json "))
        assertNull(parse("""{"displayName": "no id"}"""))
        assertNull(parse("""{"id": "ok1"}""")) // displayName is required
    }

    @Test
    fun `a future schema version is rejected rather than misread`() {
        assertNull(parse("""{"manifestVersion": 2, "id": "x1", "displayName": "X"}"""))
        assertNotNull(parse("""{"manifestVersion": 1, "id": "x1", "displayName": "X"}"""))
    }

    @Test
    fun `ids are validated as persistence keys`() {
        assertTrue(ResolverManifest.isValidId("mediafire"))
        assertTrue(ResolverManifest.isValidId("acme-host_2"))
        assertTrue(ResolverManifest.isValidId("9lives"))
        assertFalse(ResolverManifest.isValidId("Acme")) // uppercase
        assertFalse(ResolverManifest.isValidId("-lead"))
        assertFalse(ResolverManifest.isValidId("with.dot"))
        assertFalse(ResolverManifest.isValidId(""))
        assertNull(parse("""{"id": "Bad ID", "displayName": "X"}"""))
    }

    @Test
    fun `a blank display name and icon path traversal are rejected`() {
        assertNull(parse("""{"id": "x1", "displayName": "  "}"""))
        assertNull(parse("""{"id": "x1", "displayName": "X", "icons": {"png": "../escape.png"}}"""))
        // A leading slash is legal and normalized by the reader, not a rejection.
        assertNotNull(parse("""{"id": "x1", "displayName": "X", "icons": {"png": "/icons/ok.png"}}"""))
    }

    // ── Glob patterns ────────────────────────────────────────────────────────

    @Test
    fun `an empty pattern list leaves the handler unrestricted`() {
        val manifest = assertNotNull(parse("""{"id": "x1", "displayName": "X"}"""))
        assertTrue(manifest.matches("https://anything.at/all"))
    }

    @Test
    fun `globs match the full url case-insensitively and anchor both ends`() {
        val manifest = assertNotNull(
            parse(
                """
                {"id": "acme", "displayName": "Acme",
                 "urlPatterns": ["https://*.acme.example/*", "https://acme.example/f/*"]}
                """.trimIndent()
            )
        )
        assertTrue(manifest.matches("https://share.ACME.example/f/file.zip"))
        assertTrue(manifest.matches("https://acme.example/f/file.zip"))
        // Anchored: a different scheme or a suffix of the host does not match.
        assertFalse(manifest.matches("http://acme.example/f/file.zip"))
        assertFalse(manifest.matches("https://evil.example/acme.example/f/x"))
        // The trailing `/*` requires a path segment.
        assertFalse(manifest.matches("https://acme.example"))
    }

    @Test
    fun `glob literals are escaped so only the star is a wildcard`() {
        val manifest = assertNotNull(
            parse("""{"id": "a", "displayName": "A", "urlPatterns": ["https://a.com/x"]}""")
        )
        assertTrue(manifest.matches("https://a.com/x"))
        assertFalse(manifest.matches("https://axcom/x")) // `.` stays a literal dot
    }

    // ── Version comparison ───────────────────────────────────────────────────

    @Test
    fun `versionAtLeast compares numeric segments with missing parts as zero`() {
        assertTrue(ResolverManifest.versionAtLeast("1.0.0", "1.0.0"))
        assertTrue(ResolverManifest.versionAtLeast("1.4.0", "1.3.9"))
        assertTrue(ResolverManifest.versionAtLeast("2", "1.9.9"))
        assertFalse(ResolverManifest.versionAtLeast("1.2", "1.2.5"))
        assertFalse(ResolverManifest.versionAtLeast("1.0.0-beta", "1.0.1"))
        assertTrue(ResolverManifest.versionAtLeast("1.0.0-rc1", "1.0.0"))
    }

    // ── Jar loading ──────────────────────────────────────────────────────────

    private fun jarWith(vararg entries: Pair<String, String>): Path {
        val jar = Files.createTempFile("blaze-manifest", ".jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            for ((name, body) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `load reads the conventional entry from a jar without touching classes`() {
        val jar = jarWith(
            ResolverManifest.PATH to """{"id": "jar1", "displayName": "Jar One"}""",
            "com/example/Class.class" to "junk",
        )
        val manifest = ResolverManifest.load(jar)
        assertEquals("jar1", manifest?.id)
        assertEquals("Jar One", manifest?.displayName)
    }

    @Test
    fun `load returns null for a jar without the entry, a corrupt jar or an invalid body`() {
        assertNull(ResolverManifest.load(jarWith("readme.txt" to "hello")))
        assertNull(ResolverManifest.load(jarWith(ResolverManifest.PATH to "{ broken")))
        val corrupt = Files.createTempFile("blaze-corrupt", ".jar")
        Files.write(corrupt, byteArrayOf(1, 2, 3))
        assertNull(ResolverManifest.load(corrupt))
    }
}
