package org.blaze.theming

import kotlinx.coroutines.test.runTest
import org.blaze.theming.api.*
import org.blaze.theming.builtin.DefaultThemeProvider
import org.blaze.theming.builtin.MidnightThemeProvider
import org.blaze.theming.builtin.TealThemeProvider
import org.blaze.theming.core.ThemePluginLoader
import org.blaze.theming.core.ThemeRegistry
import org.blaze.theming.core.ThemeSettingsRepository
import java.nio.file.Files
import kotlin.test.*

/**
 * Covers the v2 theme contract: the Islands chrome overlay, per-theme configurable options
 * (color + ambient intensity applied by the app), mode support, palette sanitisation and the
 * in-memory/persisted/revert discipline of the settings repository.
 */
class ThemeContractV2Test {

    private fun tempDir() = Files.createTempDirectory("blaze-theme-v2")

    private fun registryWith(vararg themes: BlazeThemeProvider): ThemeRegistry {
        val dir = tempDir()
        return ThemeRegistry(
            builtIns = themes.toList(),
            pluginLoader = ThemePluginLoader(),
            settingsRepository = ThemeSettingsRepository(dir),
        )
    }

    private class PlainTheme(
        override val id: String,
        private val palette: ThemePalette = ThemePalette.Empty,
        override val supportedModes: Set<ThemeAppearanceMode> = ThemeAppearanceMode.ALL,
    ) : BlazeThemeProvider {
        override val displayName: String = id
        override val description: String = "plain $id"
        override fun provide(dark: Boolean): ThemePalette = palette
    }

    private class ConfigurableTheme(
        override val id: String,
        private val basePalette: ThemePalette = ThemePalette.Empty,
        override val optionDescriptors: List<ThemeOption> = emptyList(),
        override val supportedModes: Set<ThemeAppearanceMode> = ThemeAppearanceMode.ALL,
    ) : ConfigurableBlazeThemeProvider {
        override val displayName: String = id
        override val description: String = "cfg $id"
        override val author: String = "Tester"
        override val version: String = "9.9"
        override fun provide(dark: Boolean): ThemePalette = basePalette
    }

    // ---- Islands chrome completion --------------------------------------------------------

    @Test
    fun `an empty theme still resolves the full stock Islands chrome`() {
        val registry = registryWith(PlainTheme(ThemeRegistry.DEFAULT_THEME_ID))
        val islands = registry.paletteFor(dark = true).islands
        assertEquals(IslandsDefaults.FRAME_DARK, islands.frameArgb)
        assertEquals(IslandsDefaults.SURFACE_DARK, islands.surfaceArgb)
        assertEquals(IslandsDefaults.AMBIENT_INTENSITY_DARK_PERCENT, islands.ambientIntensityPercent)
    }

    @Test
    fun `a theme overrides only the chrome tokens it sets and inherits the rest`() {
        val registry = registryWith(
            PlainTheme(ThemeRegistry.DEFAULT_THEME_ID),
            PlainTheme(
                "custom",
                ThemePalette(islands = IslandsPalette(surfaceArgb = 0xFFABCDEF)),
            ),
        )
        registry.selectInMemory("custom")
        val islands = registry.paletteFor(dark = true).islands
        assertEquals(0xFFABCDEF, islands.surfaceArgb)                       // theme's own
        assertEquals(IslandsDefaults.FRAME_DARK, islands.frameArgb)         // stock fallback
    }

    // ---- configurable options applied by the app -------------------------------------------

    @Test
    fun `a user-picked color option overrides the theme palette without plugin code`() = runTest {
        val accentKey = "userAccent"
        val registry = registryWith(
            PlainTheme(ThemeRegistry.DEFAULT_THEME_ID),
            ConfigurableTheme(
                id = "aura",
                basePalette = ThemePalette(accentArgb = 0xFF112233),
                optionDescriptors = listOf(
                    themeColorOption(accentKey, "Accent", applyTo = ThemeColorTarget.ACCENT),
                ),
            ),
        )
        registry.select("aura")
        registry.setOptionValues("aura", mapOf(accentKey to "#FF3366FF"))

        val palette = registry.paletteFor(dark = true)
        assertEquals(0xFF3366FF, palette.accentArgb) // the user's color wins over the theme's
    }

    @Test
    fun `an ambient intensity option dials the glow and is clamped`() = runTest {
        val glowKey = "glow"
        val registry = registryWith(
            PlainTheme(ThemeRegistry.DEFAULT_THEME_ID),
            ConfigurableTheme(
                id = "glowy",
                optionDescriptors = listOf(themeAmbientIntensityOption(glowKey, "Glow strength")),
            ),
        )
        registry.select("glowy")
        registry.setOptionValues("glowy", mapOf(glowKey to "250")) // above the 0..100 range

        val islands = registry.paletteFor(dark = true).islands
        assertEquals(100, islands.ambientIntensityPercent) // clamped into range
    }

    // ---- mode support ----------------------------------------------------------------------

    @Test
    fun `an untouched color option resolves its own mode default, never the other mode's`() = runTest {
        val registry = registryWith(
            PlainTheme(ThemeRegistry.DEFAULT_THEME_ID),
            ConfigurableTheme(
                id = "two-tone",
                optionDescriptors = listOf(
                    themeColorOption(
                        "accent", "Accent", applyTo = ThemeColorTarget.ACCENT,
                        defaultArgb = 0xFF112233, defaultLightArgb = 0xFFEECCAA,
                    ),
                ),
            ),
        )
        registry.select("two-tone")
        // Untouched: dark shows the dark default, light shows the light default — the dark token no
        // longer leaks over the day palette the way it did when only one default existed.
        assertEquals(0xFF112233, registry.paletteFor(dark = true).accentArgb)
        assertEquals(0xFFEECCAA, registry.paletteFor(dark = false).accentArgb)
    }

    @Test
    fun `an explicit color override wins in both appearances`() = runTest {
        val registry = registryWith(
            PlainTheme(ThemeRegistry.DEFAULT_THEME_ID),
            ConfigurableTheme(
                id = "two-tone",
                optionDescriptors = listOf(
                    themeColorOption(
                        "accent", "Accent", applyTo = ThemeColorTarget.ACCENT,
                        defaultArgb = 0xFF112233, defaultLightArgb = 0xFFEECCAA,
                    ),
                ),
            ),
        )
        registry.select("two-tone")
        registry.setOptionValues("two-tone", mapOf("accent" to "#FF00FF00"))
        assertEquals(0xFF00FF00, registry.paletteFor(dark = true).accentArgb)
        assertEquals(0xFF00FF00, registry.paletteFor(dark = false).accentArgb)
    }

    @Test
    fun `an untouched ambient option uses the mode default while an override wins both modes`() = runTest {
        val registry = registryWith(
            PlainTheme(ThemeRegistry.DEFAULT_THEME_ID),
            ConfigurableTheme(
                id = "glow-two",
                optionDescriptors = listOf(
                    themeAmbientIntensityOption("glow", "Glow", defaultPercent = 18, defaultLightPercent = 6),
                ),
            ),
        )
        registry.select("glow-two")
        assertEquals(18, registry.paletteFor(dark = true).islands.ambientIntensityPercent)
        assertEquals(6, registry.paletteFor(dark = false).islands.ambientIntensityPercent)
        registry.setOptionValues("glow-two", mapOf("glow" to "40"))
        assertEquals(40, registry.paletteFor(dark = true).islands.ambientIntensityPercent)
        assertEquals(40, registry.paletteFor(dark = false).islands.ambientIntensityPercent)
    }

    @Test
    fun `the built-in midnight and teal themes paint day palettes in light mode`() {
        val midnight = registryWith(DefaultThemeProvider(), MidnightThemeProvider())
        midnight.selectInMemory("midnight")
        assertEquals(0xFF5B9DFF, midnight.paletteFor(dark = true).accentArgb)   // dark accent
        assertEquals(0xFF2E6FD8, midnight.paletteFor(dark = false).accentArgb)  // day accent, not the dark one

        val teal = registryWith(DefaultThemeProvider(), TealThemeProvider())
        teal.selectInMemory("teal")
        assertEquals(0xFF3FB6A8, teal.paletteFor(dark = true).accentArgb)
        assertEquals(0xFF0F9D8C, teal.paletteFor(dark = false).accentArgb)
        // Teal paints no chrome of its own, so the day frame is the stock light value, never the dark one.
        assertEquals(IslandsDefaults.FRAME_LIGHT, teal.paletteFor(dark = false).islands.frameArgb)
    }

    @Test
    fun `a dark-only theme contributes nothing in light mode`() {
        val registry = registryWith(
            PlainTheme(ThemeRegistry.DEFAULT_THEME_ID, ThemePalette(accentArgb = 0xFF0000AA)),
            PlainTheme(
                "darksolo",
                ThemePalette(accentArgb = 0xFFFFFFFF),
                supportedModes = setOf(ThemeAppearanceMode.DARK),
            ),
        )
        registry.selectInMemory("darksolo")
        // Dark mode honours the theme.
        assertEquals(0xFFFFFFFF, registry.paletteFor(dark = true).accentArgb)
        // Light mode falls back to the default theme's accent instead of stretching dark tokens.
        assertEquals(0xFF0000AA, registry.paletteFor(dark = false).accentArgb)
        // And it is not offered for light mode.
        assertTrue(registry.themesFor(ThemeAppearanceMode.LIGHT).none { it.provider.id == "darksolo" })
    }

    // ---- sanitisation ----------------------------------------------------------------------

    @Test
    fun `fully transparent and out-of-range plugin colors are dropped to the fallback`() {
        val registry = registryWith(
            PlainTheme(ThemeRegistry.DEFAULT_THEME_ID, ThemePalette(successArgb = 0xFF112233)),
            // alpha-0 accent and a negative (out-of-32-bit-range) success — both unrenderable.
            PlainTheme("bad", ThemePalette(accentArgb = 0x00FF0000, successArgb = -1L)),
        )
        registry.selectInMemory("bad")
        val palette = registry.paletteFor(dark = true)
        assertNull(palette.accentArgb)                   // dropped, default provides none
        assertEquals(0xFF112233, palette.successArgb)    // dropped, falls back to default
    }

    // ---- ThemeConfig parsing ---------------------------------------------------------------

    @Test
    fun `color hex parses opaque and alpha forms and round-trips`() {
        assertEquals(0xFFFF0000L, ThemeConfig.parseArgbHex("#FF0000"))           // 6-digit → opaque
        assertEquals(0x80FF0000L, ThemeConfig.parseArgbHex("#80FF0000"))         // 8-digit
        assertEquals(0x80FF0000L, ThemeConfig.parseArgbHex("0x80FF0000"))        // 0x-prefixed
        assertNull(ThemeConfig.parseArgbHex("not-a-color"))
        assertEquals("#12345678", ThemeConfig.encodeArgb(0x12345678L))
        val config = ThemeConfig(mapOf("c" to "#FF00AA"))
        assertEquals(0xFFFF00AAL, config.colorValue("c"))
    }

    @Test
    fun `mergedWith fills absent keys from descriptor defaults and keeps stored values`() {
        val descriptors = listOf(
            themeColorOption("a", "A", applyTo = ThemeColorTarget.ACCENT, defaultArgb = 0xFF010203),
            themeAmbientIntensityOption("g", "G", defaultPercent = 15),
        )
        val merged = ThemeConfig(mapOf("g" to "40")).mergedWith(descriptors)
        assertNotNull(merged.values["a"])          // defaulted in
        assertEquals("40", merged.values["g"])      // stored wins
        assertEquals(15, ThemeConfig(mapOf()).mergedWith(descriptors).intKeyValue("g", 15, 0L..100L))
    }

    // ---- persistence discipline ------------------------------------------------------------

    @Test
    fun `in-memory option edits are reverted to the persisted state`() = runTest {
        val repo = ThemeSettingsRepository(tempDir())
        repo.setOptionValues("aura", mapOf("k" to "persisted"))
        repo.setOptionValuesInMemory("aura", mapOf("k" to "staged"))
        assertEquals("staged", repo.settings.value.optionValues("aura").values["k"])
        repo.revertToPersisted()
        assertEquals("persisted", repo.settings.value.optionValues("aura").values["k"])
    }

    @Test
    fun `ThemePalette withFallback completes islands per token`() {
        val theme = ThemePalette(islands = IslandsPalette(surfaceArgb = 0xFF101010))
        val defaults = ThemePalette(
            accentArgb = 0xFFEEEEEE,
            islands = IslandsDefaults.forMode(dark = true),
        )
        val merged = theme.withFallback(defaults)
        assertEquals(0xFF101010, merged.islands.surfaceArgb)              // theme keeps its own
        assertEquals(IslandsDefaults.FRAME_DARK, merged.islands.frameArgb) // falls back for the rest
        assertEquals(0xFFEEEEEE, merged.accentArgb)                        // semantic fallback
    }
}
