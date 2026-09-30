package org.blaze.presentation.screens.extensions

import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.api.textOption
import org.blaze.resolver.core.LoadedResolver
import org.blaze.resolver.core.ResolverOptionsSlice
import org.blaze.resolver.core.ResolverSettings
import org.blaze.resolver.core.ResolverSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class StubResolver(override val id: String) : LinkResolver {
    override val displayName: String = id
    override val description: String = "desc-$id"
    override val iconResourcePath: String? = null
    override val iconBase64: String? = null
    override fun canResolve(url: String): Boolean = true
    override fun resolve(url: String): ResolvedLink = ResolvedLink(directUrl = "https://direct/$id")
}

/**
 * The panel's row mapping is pure, so it can be checked against a fake handler list and settings
 * snapshot without a coroutine scope or a live registry: enable state, ordering priority, the
 * built-in/plugin tag, and which handler exposes options all come straight through.
 */
class ExtensionsScreenModelTest {

    @Test
    fun `rows reflect enable state, priority, source and declared options`() {
        val alpha = LoadedResolver(StubResolver("alpha"), ResolverSource.BUILTIN)
        val beta = LoadedResolver(StubResolver("beta"), ResolverSource.PLUGIN)
        val settings = ResolverSettings(
            disabledHandlers = listOf("beta"),
            handlerOptions = mapOf("alpha" to ResolverOptionsSlice(priority = 5)),
        )
        val descriptors: List<ResolverOption> = listOf(textOption("token", "Token", default = "x"))

        val rows = buildExtensionRows(listOf(alpha, beta), settings) { loaded ->
            if (loaded.resolver.id == "alpha") descriptors else emptyList()
        }

        val alphaRow = rows.first { it.id == "alpha" }
        assertTrue(alphaRow.enabled)
        assertEquals(5, alphaRow.priority)
        assertFalse(alphaRow.isPlugin)
        assertEquals(descriptors, alphaRow.options)

        val betaRow = rows.first { it.id == "beta" }
        assertFalse(betaRow.enabled)
        assertTrue(betaRow.isPlugin)
        assertTrue(betaRow.options.isEmpty())
    }

    @Test
    fun `an empty handler list yields no rows`() {
        val rows = buildExtensionRows(emptyList(), ResolverSettings()) { emptyList() }
        assertEquals(emptyList(), rows)
    }
}
