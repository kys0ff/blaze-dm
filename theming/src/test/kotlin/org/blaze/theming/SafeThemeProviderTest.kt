package org.blaze.theming

import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.ThemePalette
import org.blaze.theming.core.SafeThemeProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ExplosiveTheme : BlazeThemeProvider {
    override val id: String get() = error("boom id")
    override val displayName: String get() = error("boom name")
    override val description: String get() = error("boom desc")
    override fun provide(dark: Boolean): ThemePalette = error("boom provide")
}

private class GoodTheme : BlazeThemeProvider {
    override val id = "teal"
    override val displayName = "Teal"
    override val description = "teal-ish"
    override fun provide(dark: Boolean) = ThemePalette(accentArgb = 0xFF00AAAA)
}

class SafeThemeProviderTest {

    @Test
    fun `a throwing plugin is contained and falls back instead of crashing`() {
        val safe = SafeThemeProvider(ExplosiveTheme(), pluginPath = null)

        // id can't be read, so a stable synthetic identity is used and stays memoized-stable.
        assertTrue(safe.id.startsWith("invalid:"))
        assertEquals(safe.id, safe.id)
        // displayName falls back to the (synthetic) id; description to empty.
        assertEquals(safe.id, safe.displayName)
        assertEquals("", safe.description)
        // provide returning an Error/Exception yields the empty palette, never a crash.
        assertEquals(ThemePalette.Empty, safe.provide(dark = true))
    }

    @Test
    fun `a well-behaved plugin is passed through unchanged`() {
        val safe = SafeThemeProvider(GoodTheme(), pluginPath = null)

        assertEquals("teal", safe.id)
        assertEquals("Teal", safe.displayName)
        assertEquals("teal-ish", safe.description)
        assertEquals(0xFF00AAAA, safe.provide(dark = false).accentArgb)
    }

    @Test
    fun `fallback id names the offending jar when available`() {
        val safe = SafeThemeProvider(ExplosiveTheme(), pluginPath = java.nio.file.Path.of("/x/cool-theme.jar"))
        assertTrue(safe.id.contains("cool-theme.jar"))
    }
}
