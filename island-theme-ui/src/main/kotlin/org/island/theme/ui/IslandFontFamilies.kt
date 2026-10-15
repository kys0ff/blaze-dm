package org.island.theme.ui

import androidx.compose.ui.text.ExperimentalTextApi
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
import java.awt.GraphicsEnvironment
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import androidx.compose.ui.text.platform.Font as ComposeFont
import java.awt.Font as AwtFont

/**
 * The font catalogue the Islands theme renders with, and the source of Settings' font dropdowns.
 *
 * Three kinds of entry live here:
 *  - Jewel's own bundles — Inter (the IDE interface font) and JetBrains Mono (its code font) — which
 *    ship as classpath resources inside Jewel's standalone jar and need no binary in this repo;
 *  - Blaze's *own* bundles — a few free, redistribution-licensed families (Roboto, IBM Plex Sans,
 *    Source Code Pro) checked in under `fonts/bundled/<slug>/` (see the `downloadBundledFonts`
 *    Gradle task) so the picker offers more than the two Jewel families; and
 *  - the OS's installed families, enumerated through AWT's [GraphicsEnvironment] and addressed by
 *    name via `FontFamily(name)`.
 *
 * Settings persists the choice as an opaque id string: a bare id for a bundled family, `system` for
 * the platform default, or a `system:<Family Name>` id for an installed family. An unknown id — a
 * hand-edited app.json, a family dropped in a future release, a saved font missing on another
 * machine — falls back to the default exactly like an unknown language code, so the UI can never
 * end up unrenderable.
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalTextApi::class)
object IslandFontFamilies {

    /** The OS-default family (Compose `FontFamily.Default`; `Monospace` for the mono slot). */
    const val SYSTEM = "system"

    /** Jewel's bundled Inter — the stock New UI interface font and Blaze's default. */
    const val INTER = "inter"

    /** Jewel's bundled JetBrains Mono — the IDE's coding font. */
    const val JETBRAINS_MONO = "jetbrains_mono"

    /** Blaze's own bundled Roboto (Apache-2.0) — a UI-friendly sans. */
    const val ROBOTO = "roboto"

    /** Blaze's own bundled IBM Plex Sans (OFL) — IBM's interface grotesque. */
    const val IBM_PLEX_SANS = "ibm_plex_sans"

    /** Blaze's own bundled Source Code Pro (OFL) — Adobe's monospace. */
    const val SOURCE_CODE_PRO = "source_code_pro"

    /** Prefix marking a persisted id as a specific OS-installed family (`system:Consolas`). */
    const val SYSTEM_PREFIX = "system:"

    /** The ids bundled in the catalogue — everything except the machine-specific OS families. */
    val availableIds: List<String> = listOf(SYSTEM, INTER, JETBRAINS_MONO, ROBOTO, IBM_PLEX_SANS, SOURCE_CODE_PRO)

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

    /** Classpath root Blaze's *own* downloaded families live under (see `downloadBundledFonts`). */
    private const val BundledRoot = "/fonts/bundled"

    /**
     * A selectable family: its Compose [family] plus the bundled regular face ([regularResource])
     * whose vertical metrics define the line height — `null` for OS families, which have no
     * resource to measure and keep the baseline height.
     */
    data class IslandFont(val id: String, val family: FontFamily, val regularResource: String?)

    /**
     * One row of a Settings font dropdown: the persisted [id], its human [label], and the [family]
     * the picker previews the name in. Unlike [IslandFont] (what the theme resolves to), this also
     * carries the display name, which the dropdown needs but the substitution path never does.
     */
    data class FontChoice(val id: String, val label: String, val family: FontFamily)

    /** The interface family for [id]; anything unknown resolves to the stock [INTER]. */
    fun uiFontFor(id: String): IslandFont = resolve(id, FontFamily.Default, interFont)

    /** The monospace family for [id]; anything unknown resolves to the stock [JETBRAINS_MONO]. */
    fun monoFontFor(id: String): IslandFont = resolve(id, FontFamily.Monospace, jetBrainsMonoFont)

    /**
     * Map a persisted [id] onto a family: `system` to the slot's platform default, a `system:<name>`
     * id to that OS family addressed by name, a bundled id to its entry, and anything else to [fallback].
     */
    private fun resolve(id: String, systemFamily: FontFamily, fallback: IslandFont): IslandFont = when {
        id == SYSTEM -> IslandFont(SYSTEM, systemFamily, null)
        id.startsWith(SYSTEM_PREFIX) -> IslandFont(id, FontFamily(id.removePrefix(SYSTEM_PREFIX)), null)
        else -> byId[id] ?: fallback
    }

    /**
     * The dropdown list for the interface font. The localized [systemDefaultLabel] names the OS-default
     * entry; the bundled families keep their endonym labels (proper nouns, like the language row) and the
     * OS families are appended, sorted.
     */
    fun uiChoices(systemDefaultLabel: String): List<FontChoice> =
        buildChoices(FontFamily.Default, systemDefaultLabel)

    /** The dropdown list for the monospace font; same shape as [uiChoices], mono-slot default. */
    fun monoChoices(systemDefaultLabel: String): List<FontChoice> =
        buildChoices(FontFamily.Monospace, systemDefaultLabel)

    /** Bundled catalogue entries in presentation order — Jewel's two first, then Blaze's own. */
    private val bundledChoices: List<FontChoice> by lazy {
        listOf(
            FontChoice(INTER, "Inter", FontFamily.Inter),
            FontChoice(JETBRAINS_MONO, "JetBrains Mono", jetBrainsMono),
            FontChoice(ROBOTO, "Roboto", roboto),
            FontChoice(IBM_PLEX_SANS, "IBM Plex Sans", ibmPlexSans),
            FontChoice(SOURCE_CODE_PRO, "Source Code Pro", sourceCodePro),
        )
    }

    /**
     * The OS-installed families, each addressed by name. Cached because the set is fixed for a
     * process; wrapped defensively because the enumeration throws on a headless JVM (unit tests).
     */
    private val systemChoices: List<FontChoice> by lazy {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .sortedBy { it.lowercase() }
                .map { name -> FontChoice(SYSTEM_PREFIX + name, name, FontFamily(name)) }
        }.getOrElse { emptyList() }
    }

    private fun buildChoices(defaultFamily: FontFamily, systemDefaultLabel: String): List<FontChoice> =
        listOf(FontChoice(SYSTEM, systemDefaultLabel, defaultFamily)) + bundledChoices + systemChoices

    // The bundled families by id, kept so resolution and the picker read one source. Built lazily so
    // the (possibly missing) TTFs are only touched when a family is actually chosen or previewed.
    private val byId: Map<String, IslandFont> by lazy {
        bundledChoices.associate { choice ->
            choice.id to IslandFont(choice.id, choice.family, regularResourceFor(choice.id))
        } + (INTER to interFont) + (JETBRAINS_MONO to jetBrainsMonoFont)
    }

    private val interFont: IslandFont get() = IslandFont(INTER, FontFamily.Inter, InterRegularResource)
    private val jetBrainsMonoFont: IslandFont
        get() = IslandFont(
            JETBRAINS_MONO,
            jetBrainsMono,
            JetBrainsMonoRegularResource
        )

    /** The regular face whose metrics drive line height, for the Blaze-bundled families. */
    private fun regularResourceFor(id: String): String? = when (id) {
        ROBOTO -> "$BundledRoot/roboto/Roboto-Regular.ttf"
        IBM_PLEX_SANS -> "$BundledRoot/ibm_plex_sans/IBMPlexSans-Regular.ttf"
        SOURCE_CODE_PRO -> "$BundledRoot/source_code_pro/SourceCodePro-Regular.ttf"
        else -> null
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

    // ── Blaze's own downloaded families (see the downloadBundledFonts Gradle task) ────────────────
    // Each carries Regular / Bold faces plus their Italic counterparts; Jewel ramps rarely go past
    // Bold, and a heavier requested weight resolves to the nearest face Compose has.

    private val roboto: FontFamily by lazy {
        fourFaceFamily("$BundledRoot/roboto/Roboto", "Regular", "Bold", "Italic", "BoldItalic")
    }

    private val ibmPlexSans: FontFamily by lazy {
        fourFaceFamily("$BundledRoot/ibm_plex_sans/IBMPlexSans", "Regular", "Bold", "Italic", "BoldItalic")
    }

    private val sourceCodePro: FontFamily by lazy {
        fourFaceFamily(
            "$BundledRoot/source_code_pro/SourceCodePro",
            "Regular", "Bold", "Italic", "BoldItalic",
        )
    }

    private fun fourFaceFamily(
        base: String,
        regular: String,
        bold: String,
        italic: String,
        boldItalic: String,
    ): FontFamily = FontFamily(
        ComposeFont(resource = "$base-$regular.ttf", weight = FontWeight.Normal),
        ComposeFont(resource = "$base-$italic.ttf", weight = FontWeight.Normal, style = FontStyle.Italic),
        ComposeFont(resource = "$base-$bold.ttf", weight = FontWeight.Bold),
        ComposeFont(resource = "$base-$boldItalic.ttf", weight = FontWeight.Bold, style = FontStyle.Italic),
    )
}
