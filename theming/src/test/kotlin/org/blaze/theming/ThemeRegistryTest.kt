package org.blaze.theming

import kotlinx.coroutines.test.runTest
import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.ThemePalette
import org.blaze.theming.core.ThemePluginLoader
import org.blaze.theming.core.ThemeRegistry
import org.blaze.theming.core.ThemeSettingsRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTheme(
    override val id: String,
    private val palette: ThemePalette = ThemePalette.Empty
) : BlazeThemeProvider {
    override val displayName: String = id
    override val description: String = "fake"
    override fun provide(dark: Boolean): ThemePalette = palette
}

class ThemeRegistryTest {

    private fun tempDir() = Files.createTempDirectory("blaze-theme")

    private fun registryWith(vararg themes: BlazeThemeProvider): ThemeRegistry {
        val dir = tempDir()
        return ThemeRegistry(
            builtIns = themes.toList(),
            pluginLoader = ThemePluginLoader(),
            settingsRepository = ThemeSettingsRepository(dir)
        )
    }

    @Test
    fun `built-ins are registered and default theme is preselected`() {
        val registry = registryWith(
            FakeTheme(ThemeRegistry.DEFAULT_THEME_ID),
            FakeTheme("teal")
        )
        assertEquals(listOf("default", "teal"), registry.themes.value.map { it.provider.id })
        assertEquals(ThemeRegistry.DEFAULT_THEME_ID, registry.selectedThemeId.value)
    }

    @Test
    fun `select changes the active theme`() = runTest {
        val registry = registryWith(
            FakeTheme(ThemeRegistry.DEFAULT_THEME_ID),
            FakeTheme("teal")
        )
        registry.select("teal")
        assertEquals("teal", registry.selectedThemeId.value)
    }

    @Test
    fun `selecting an unknown theme is ignored`() = runTest {
        val registry = registryWith(FakeTheme(ThemeRegistry.DEFAULT_THEME_ID))
        registry.select("nope")
        assertEquals(ThemeRegistry.DEFAULT_THEME_ID, registry.selectedThemeId.value)
    }

    @Test
    fun `paletteFor resolves the selected theme palette`() = runTest {
        val registry = registryWith(
            FakeTheme(ThemeRegistry.DEFAULT_THEME_ID),
            FakeTheme("teal", ThemePalette(accentArgb = 0xFF00AAAA))
        )
        registry.select("teal")
        assertEquals(0xFF00AAAA, registry.paletteFor(dark = true).accentArgb)
    }

    @Test
    fun `partial theme falls back to the default theme for unset colours`() = runTest {
        val registry = registryWith(
            FakeTheme(ThemeRegistry.DEFAULT_THEME_ID, ThemePalette(successArgb = 0xFF112233)),
            FakeTheme("teal", ThemePalette(accentArgb = 0xFF00AAAA))
        )
        registry.select("teal")
        val palette = registry.paletteFor(dark = false)
        assertEquals(0xFF00AAAA, palette.accentArgb)         // from teal
        assertEquals(0xFF112233, palette.successArgb)        // fallback from default
        assertNull(palette.errorArgb)                         // neither provides it
    }

    @Test
    fun `built-in themes cannot be removed`() = runTest {
        val registry = registryWith(FakeTheme(ThemeRegistry.DEFAULT_THEME_ID))
        val result = registry.uninstall(ThemeRegistry.DEFAULT_THEME_ID)
        assertTrue(result.isFailure)
    }

    @Test
    fun `removing an unknown theme fails`() = runTest {
        val registry = registryWith(FakeTheme(ThemeRegistry.DEFAULT_THEME_ID))
        // uninstall returns a failed Result rather than throwing.
        assertTrue(registry.uninstall("ghost").isFailure)
    }
}
