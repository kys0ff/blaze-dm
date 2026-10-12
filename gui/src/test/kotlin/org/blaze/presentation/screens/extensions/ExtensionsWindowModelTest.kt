package org.blaze.presentation.screens.extensions

import org.blaze.resolver.api.IconFormat
import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverIcon
import org.blaze.resolver.api.ResolverManifest
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.api.textOption
import org.blaze.resolver.core.LoadedResolver
import org.blaze.resolver.core.ResolverOptionsSlice
import org.blaze.resolver.core.ResolverSettings
import org.blaze.resolver.core.ResolverSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behavior only - everything the row shows comes from the manifest paired with it. */
private class StubResolver : LinkResolver {
    override fun canResolve(url: String): Boolean = true
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = "https://direct/x")
}

private fun loaded(
    id: String,
    source: ResolverSource,
    defaultPriority: Int? = null,
): LoadedResolver = LoadedResolver(
    id = id,
    manifest = ResolverManifest(
        id = id,
        displayName = "Handler $id",
        description = "desc-$id",
        author = "Acme",
        version = "1.2.3",
        homepage = "https://acme.example/$id",
        defaultPriority = defaultPriority,
    ),
    resolver = StubResolver(),
    source = source,
)

/**
 * The panel's row mapping is pure, so it can be checked against a fake handler list and settings
 * snapshot without a coroutine scope or a live registry: every metadata field is projected off the
 * handler's manifest (enable state, ordering priority, the built-in/plugin tag, declared options
 * and the preloaded light/dark icons all come straight through).
 */
class ExtensionsWindowModelTest {

    @Test
    fun `rows are built from the manifest with enable state, priority, source and options`() {
        val alpha = loaded("alpha", ResolverSource.BUILTIN)
        val beta = loaded("beta", ResolverSource.PLUGIN, defaultPriority = 4)
        val settings = ResolverSettings(
            disabledHandlers = listOf("beta"),
            handlerOptions = mapOf("alpha" to ResolverOptionsSlice(priority = 5)),
        )
        val descriptors: List<ResolverOption> = listOf(textOption("token", "Token", default = "x"))
        val lightIcon = ResolverIcon(byteArrayOf(1, 2, 3), IconFormat.PNG)
        val darkIcon = ResolverIcon(byteArrayOf(4, 5, 6), IconFormat.SVG)

        val rows = buildExtensionRows(
            listOf(alpha, beta),
            settings,
            iconsOf = { loaded ->
                if (loaded.id == "alpha") lightIcon to darkIcon else null to null
            },
            descriptorsOf = { loaded ->
                if (loaded.id == "alpha") descriptors else emptyList()
            },
        )

        val alphaRow = rows.first { it.id == "alpha" }
        assertEquals("Handler alpha", alphaRow.displayName)
        assertEquals("desc-alpha", alphaRow.description)
        assertEquals("Acme", alphaRow.author)
        assertEquals("1.2.3", alphaRow.version)
        assertEquals("https://acme.example/alpha", alphaRow.homepage)
        assertTrue(alphaRow.enabled)
        // The user's stored weight beats the manifest default.
        assertEquals(5, alphaRow.priority)
        assertFalse(alphaRow.isPlugin)
        assertEquals(descriptors, alphaRow.options)
        assertEquals(lightIcon, alphaRow.icon)
        assertEquals(darkIcon, alphaRow.darkIcon)

        val betaRow = rows.first { it.id == "beta" }
        assertFalse(betaRow.enabled)
        assertTrue(betaRow.isPlugin)
        assertTrue(betaRow.options.isEmpty())
        assertNull(betaRow.icon)
        // Untouched by the user, beta falls back to its manifest's defaultPriority.
        assertEquals(4, betaRow.priority)
    }

    @Test
    fun `priority falls back to zero when neither user nor manifest declares one`() {
        val rows = buildExtensionRows(
            listOf(loaded("plain", ResolverSource.BUILTIN)),
            ResolverSettings(),
            descriptorsOf = { emptyList() },
        )
        assertEquals(0, rows.single().priority)
    }

    @Test
    fun `an empty handler list yields no rows`() {
        val rows = buildExtensionRows(emptyList(), ResolverSettings(), descriptorsOf = { emptyList() })
        assertEquals(emptyList(), rows)
    }
}
