package org.island.theme.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlin.test.*

/**
 * Guards the font catalogue Settings persists against: id mapping, fallbacks for unknown ids,
 * line-height recomputation for bundled families, and the classpath faces Blaze's hand-built
 * JetBrains Mono family depends on.
 */
class IslandFontFamiliesTest {

    @Test
    fun `text scale percent is clamped to the readable range`() {
        assertEquals(100, IslandFontFamilies.normalizeTextScalePercent(100))
        assertEquals(60, IslandFontFamilies.normalizeTextScalePercent(59))
        assertEquals(60, IslandFontFamilies.normalizeTextScalePercent(Int.MIN_VALUE))
        assertEquals(160, IslandFontFamilies.normalizeTextScalePercent(161))
        assertEquals(160, IslandFontFamilies.normalizeTextScalePercent(Int.MAX_VALUE))
    }

    @Test
    fun `ui font ids map to their families and metrics sources`() {
        val system = IslandFontFamilies.uiFontFor(IslandFontFamilies.SYSTEM)
        assertEquals(FontFamily.Default, system.family)
        assertNull(system.regularResource, "the OS family has nothing to measure, by design")

        val inter = IslandFontFamilies.uiFontFor(IslandFontFamilies.INTER)
        assertEquals(IslandFontFamilies.INTER, inter.id)
        assertNotNull(inter.regularResource)

        val mono = IslandFontFamilies.uiFontFor(IslandFontFamilies.JETBRAINS_MONO)
        assertEquals(IslandFontFamilies.JETBRAINS_MONO, mono.id)
        assertNotNull(mono.regularResource)
    }

    @Test
    fun `unknown font ids fall back to the stock defaults`() {
        // Same promise the language code makes: a hand-edited app.json can never unrender the UI.
        assertEquals(IslandFontFamilies.INTER, IslandFontFamilies.uiFontFor("comic_sans").id)
        assertEquals(IslandFontFamilies.DefaultUiFontId, IslandFontFamilies.uiFontFor("").id)
        assertEquals(IslandFontFamilies.JETBRAINS_MONO, IslandFontFamilies.monoFontFor("nope").id)
        assertEquals(IslandFontFamilies.DefaultMonoFontId, IslandFontFamilies.monoFontFor(" ").id)
    }

    @Test
    fun `mono system id maps to the platform monospace`() {
        val system = IslandFontFamilies.monoFontFor(IslandFontFamilies.SYSTEM)
        assertEquals(FontFamily.Monospace, system.family)
        assertNull(system.regularResource)
    }

    @Test
    fun `system-prefixed ids resolve to the named OS family with nothing to measure`() {
        // An OS family has no bundled regular face, so it keeps the base line height by design.
        val consolas = IslandFontFamilies.monoFontFor("${IslandFontFamilies.SYSTEM_PREFIX}Consolas")
        assertEquals("${IslandFontFamilies.SYSTEM_PREFIX}Consolas", consolas.id)
        assertNull(consolas.regularResource)
        assertNotNull(consolas.family)
    }

    @Test
    fun `blaze's own bundled families resolve with a measurable regular face`() {
        listOf(IslandFontFamilies.ROBOTO, IslandFontFamilies.IBM_PLEX_SANS, IslandFontFamilies.SOURCE_CODE_PRO)
            .forEach { id ->
                val font = IslandFontFamilies.uiFontFor(id)
                assertEquals(id, font.id, "$id must resolve to itself, not the fallback")
                assertNotNull(font.regularResource, "$id needs a bundled face to measure line height")
                assertNotNull(font.family)
            }
    }

    @Test
    fun `dropdown choices lead with the localized default then every bundled family`() {
        val choices = IslandFontFamilies.uiChoices("System default")
        assertEquals(IslandFontFamilies.SYSTEM, choices.first().id)
        assertEquals("System default", choices.first().label)
        val ids = choices.map { it.id }
        assertTrue(
            ids.containsAll(
                listOf(
                    IslandFontFamilies.INTER,
                    IslandFontFamilies.JETBRAINS_MONO,
                    IslandFontFamilies.ROBOTO
                )
            )
        )
        assertTrue(ids.distinctBy { it }.size == ids.size, "choice ids must be unique")
    }

    @Test
    fun `bundled families report a line height while os families report none`() {
        val inter = IslandFontFamilies.uiFontFor(IslandFontFamilies.INTER)
        val interLineHeight = assertNotNull(IslandFontFamilies.lineHeightSp(inter, 13f))
        assertTrue(interLineHeight.isSp)
        // Jewel's own default styles for 13.sp Inter: the catalogue must reproduce the same value.
        assertTrue(interLineHeight.value in 10f..30f, "implausible Inter line height: $interLineHeight")

        val mono = IslandFontFamilies.uiFontFor(IslandFontFamilies.JETBRAINS_MONO)
        assertNotNull(IslandFontFamilies.lineHeightSp(mono, 13f))

        assertNull(IslandFontFamilies.lineHeightSp(IslandFontFamilies.uiFontFor(IslandFontFamilies.SYSTEM), 13f))
    }

    @Test
    fun `substituteInto swaps the family and recomputes the line height for bundled fonts`() {
        val base = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp, lineHeight = 16.sp)

        val osFont = IslandFontFamilies.uiFontFor(IslandFontFamilies.SYSTEM)
        val keptHeight = IslandFontFamilies.substituteInto(base, osFont)
        assertEquals(FontFamily.Default, keptHeight.fontFamily)
        assertEquals(16.sp, keptHeight.lineHeight, "an unmeasurable family keeps the base line height")

        val jbm = IslandFontFamilies.uiFontFor(IslandFontFamilies.JETBRAINS_MONO)
        val substituted = IslandFontFamilies.substituteInto(base, jbm)
        assertSame(jbm.family, substituted.fontFamily)
        assertEquals(IslandFontFamilies.lineHeightSp(jbm, 13f), substituted.lineHeight)
    }

    @Test
    fun `every jetbrains mono face blaze builds the family from is on the classpath`() {
        // Jewel's own FontFamily.JetBrainsMono references Black faces the jar does not ship; Blaze
        // builds the family from the faces that exist, so this test pins exactly that set down.
        val faces = listOf(
            "Thin", "ThinItalic", "ExtraLight", "ExtraLightItalic",
            "Light", "LightItalic", "Regular", "Italic",
            "Medium", "MediumItalic", "SemiBold", "SemiBoldItalic",
            "Bold", "BoldItalic", "ExtraBold", "ExtraBoldItalic",
        )
        faces.forEach { face ->
            val resource = "/fonts/jetbrains-mono/JetBrainsMono-$face.ttf"
            assertNotNull(
                IslandFontFamiliesTest::class.java.getResourceAsStream(resource),
                "Jewel's jar is expected to ship $resource",
            )
        }
        // Touching the lazy family must not throw (faces resolve without missing resources).
        assertNotNull(IslandFontFamilies.uiFontFor(IslandFontFamilies.JETBRAINS_MONO).family)
    }
}
