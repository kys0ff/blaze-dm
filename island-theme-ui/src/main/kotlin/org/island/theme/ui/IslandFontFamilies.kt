package org.island.theme.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.intui.standalone.Inter
import org.jetbrains.jewel.ui.component.computeLineHeightPx
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import androidx.compose.ui.text.platform.Font as ComposeFont
import java.awt.Font as AwtFont

/**
 * The font catalogue the Islands theme renders with.
 *
 * Blaze ships no font binaries of its own: Jewel's standalone artifact already bundles the two
 * families the New UI is built on — Inter (the IDE's interface font) and JetBrains Mono (its code
 * font) — as classpath resources, so a customization re-points the theme's text styles at a
 * different bundled family (or at the OS default) instead of duplicating glyphs in the repo.
 *
 * Settings persist these ids as opaque strings. An unknown id — a hand-edited app.json, a family
 * dropped from a future Jewel — falls back to the default, exactly like an unknown language code,
 * so the UI can never end up unrenderable.
 */
@OptIn(ExperimentalJewelApi::class)
object IslandFontFamilies {

    /** The OS-default family (Compose `FontFamily.Default`; `Monospace` for the mono slot). */
    const val SYSTEM = "system"

    /** Jewel's bundled Inter — the stock New UI interface font and Blaze's default. */
    const val INTER = "inter"

    /** Jewel's bundled JetBrains Mono — the IDE's coding font. */
    const val JETBRAINS_MONO = "jetbrains_mono"

    /** Catalogue ids, in the order Settings presents them. */
    val availableIds: List<String> = listOf(SYSTEM, INTER, JETBRAINS_MONO)

    /** The family Blaze renders with when nothing is configured — Jewel's stock interface font. */
    const val DefaultUiFontId = INTER

    /** The stock monospace — also Jewel's own editor/console default. */
    const val DefaultMonoFontId = JETBRAINS_MONO

    const val DefaultTextScalePercent = 100
    const val MinTextScalePercent = 60
    const val MaxTextScalePercent = 160

    /** Clamp a persisted percentage so a hand-edited file can never render 1px or 400% text. */
    fun normalizeTextScalePercent(percent: Int): Int = percent.coerceIn(MinTextScalePercent, MaxTextScalePercent)

    /**
     * The line-height multiplier Jewel applies to editor/console metrics: the 0.87 Skia/Swing
     * compensation baked into its `computeJetBrainsMonoLineHeightPx` times its 1.2 editor factor.
     * Keeping the product here makes a substituted mono family sit as tightly as the stock one.
     */
    const val MonoLineHeightMultiplier: Float = 0.87f * 1.2f

    private const val InterRegularResource = "/fonts/inter/Inter-Regular.ttf"
    private const val JetBrainsMonoRegularResource = "/fonts/jetbrains-mono/JetBrainsMono-Regular.ttf"

    /**
     * A selectable family: its Compose [family] plus the bundled regular face ([regularResource])
     * whose vertical metrics define the line height — `null` for OS families, which have no
     * resource to measure and keep the base line height.
     */
    data class IslandFont(val id: String, val family: FontFamily, val regularResource: String?)

    /** The interface family for [id]; anything unknown resolves to the stock [INTER]. */
    fun uiFontFor(id: String): IslandFont = when (id) {
        SYSTEM -> IslandFont(SYSTEM, FontFamily.Default, null)
        JETBRAINS_MONO -> IslandFont(JETBRAINS_MONO, jetBrainsMono, JetBrainsMonoRegularResource)
        else -> IslandFont(INTER, FontFamily.Inter, InterRegularResource)
    }

    /** The monospace family for [id]; anything unknown resolves to the stock [JETBRAINS_MONO]. */
    fun monoFontFor(id: String): IslandFont = when (id) {
        SYSTEM -> IslandFont(SYSTEM, FontFamily.Monospace, null)
        INTER -> IslandFont(INTER, FontFamily.Inter, InterRegularResource)
        else -> IslandFont(JETBRAINS_MONO, jetBrainsMono, JetBrainsMonoRegularResource)
    }

    /**
     * Rewrite [style] to render with [font]'s family and — when the family is a bundled resource —
     * with a line height recomputed from that family's own vertical metrics at the style's size.
     * Jewel derives every line height from Inter, so substituting a family with different vertical
     * metrics without recomputing would stack the lines tighter (or looser) than the font intends.
     */
    fun substituteInto(style: TextStyle, font: IslandFont, lineHeightMultiplier: Float = 1f): TextStyle {
        val withFamily = style.copy(fontFamily = font.family)
        val size = withFamily.fontSize
        if (font.regularResource == null || !size.isSp) return withFamily
        val lineHeight = lineHeightSp(font, size.value, lineHeightMultiplier) ?: return withFamily
        return withFamily.copy(lineHeight = lineHeight)
    }

    /** The line height (in sp) of [font]'s regular face at [fontSize], or null when it has no measurable resource. */
    fun lineHeightSp(font: IslandFont, fontSize: Float, multiplier: Float = 1f): TextUnit? {
        val resource = font.regularResource ?: return null
        // Mirrors Jewel: measure the AWT line height at the (sp-valued) size and express it in sp,
        // so the lineHeight tracks the font size through density changes exactly like the stock styles do.
        val px = lineHeightPx.getOrPut("$resource@$fontSize@$multiplier") {
            (awtFont(resource).deriveFont(fontSize).computeLineHeightPx() * multiplier).roundToInt()
        }
        return px.sp
    }

    private val awtFonts = ConcurrentHashMap<String, AwtFont>()
    private val lineHeightPx = ConcurrentHashMap<String, Int>()

    private fun awtFont(resource: String): AwtFont = awtFonts.getOrPut(resource) {
        val stream = IslandFontFamilies::class.java.getResourceAsStream(resource)
            ?: error("Bundled font '$resource' is missing from the classpath (Jewel's standalone jar?)")
        try {
            stream.use { AwtFont.createFont(AwtFont.TRUETYPE_FONT, it) }
        } catch (e: Exception) {
            error("Bundled font '$resource' could not be loaded: ${e.message}")
        }
    }

    /**
     * JetBrains Mono with the eight weights Jewel actually ships (Thin→ExtraBold, plus italics).
     * Jewel's own `FontFamily.JetBrainsMono` additionally lists Black faces whose TTFs are *not* in
     * the jar, and a family holding a missing resource throws the moment it is resolved — so Blaze
     * builds the family from the faces that exist; a requested weight 900 then resolves to ExtraBold.
     */
    private val jetBrainsMono: FontFamily by lazy {
        FontFamily(
            jetBrainsMonoFace("Thin", FontWeight.Thin),
            jetBrainsMonoFace("ThinItalic", FontWeight.Thin, FontStyle.Italic),
            jetBrainsMonoFace("ExtraLight", FontWeight.ExtraLight),
            jetBrainsMonoFace("ExtraLightItalic", FontWeight.ExtraLight, FontStyle.Italic),
            jetBrainsMonoFace("Light", FontWeight.Light),
            jetBrainsMonoFace("LightItalic", FontWeight.Light, FontStyle.Italic),
            jetBrainsMonoFace("Regular", FontWeight.Normal),
            jetBrainsMonoFace("Italic", FontWeight.Normal, FontStyle.Italic),
            jetBrainsMonoFace("Medium", FontWeight.Medium),
            jetBrainsMonoFace("MediumItalic", FontWeight.Medium, FontStyle.Italic),
            jetBrainsMonoFace("SemiBold", FontWeight.SemiBold),
            jetBrainsMonoFace("SemiBoldItalic", FontWeight.SemiBold, FontStyle.Italic),
            jetBrainsMonoFace("Bold", FontWeight.Bold),
            jetBrainsMonoFace("BoldItalic", FontWeight.Bold, FontStyle.Italic),
            jetBrainsMonoFace("ExtraBold", FontWeight.ExtraBold),
            jetBrainsMonoFace("ExtraBoldItalic", FontWeight.ExtraBold, FontStyle.Italic),
        )
    }

    private fun jetBrainsMonoFace(
        face: String,
        weight: FontWeight,
        style: FontStyle = FontStyle.Normal,
    ): Font = ComposeFont(
        resource = "/fonts/jetbrains-mono/JetBrainsMono-$face.ttf",
        weight = weight,
        style = style,
    )
}
