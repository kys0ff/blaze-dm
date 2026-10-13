package org.island.theme.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import org.jetbrains.jewel.ui.Typography

/**
 * A [Typography] decorator that re-faces the ramps of a [delegate] with the user's chosen families.
 *
 * Jewel builds every typography ramp from Inter inside keyless `remember` blocks, so a font change
 * cannot reach them through the [org.jetbrains.jewel.foundation.theme.ThemeDefinition] text styles
 * alone — the theme therefore re-provides `LocalTypography` with this wrapper. Each member delegates
 * to the stock typography and then substitutes the family (and, for bundled families, the line
 * height recomputed from that family's own metrics) into the resulting style.
 *
 * Both substitutions are individually switchable: with both off, [IslandTheme] simply keeps the
 * delegate, so default settings render bit-identically to plain Jewel.
 */
class FontSubstitutingTypography(
    private val delegate: Typography,
    private val uiFont: IslandFontFamilies.IslandFont,
    private val monoFont: IslandFontFamilies.IslandFont,
    private val substituteUi: Boolean,
    private val substituteMono: Boolean,
) : Typography {

    @Composable
    override fun rememberDefaultTextStyle(
        fontSize: TextUnit,
        fontWeight: FontWeight?,
        fontStyle: FontStyle?
    ): TextStyle =
        ui(delegate.rememberDefaultTextStyle(fontSize, fontWeight, fontStyle))

    @Composable
    override fun rememberEditorTextStyle(
        fontSize: TextUnit,
        fontWeight: FontWeight?,
        fontStyle: FontStyle?
    ): TextStyle =
        mono(delegate.rememberEditorTextStyle(fontSize, fontWeight, fontStyle))

    @Composable
    override fun rememberConsoleTextStyle(
        fontSize: TextUnit,
        fontWeight: FontWeight?,
        fontStyle: FontStyle?
    ): TextStyle =
        mono(delegate.rememberConsoleTextStyle(fontSize, fontWeight, fontStyle))

    @get:Composable
    override val labelTextStyle: TextStyle get() = ui(delegate.labelTextStyle)

    @get:Composable
    override val labelTextSize: TextUnit get() = delegate.labelTextSize

    @get:Composable
    override val h0TextStyle: TextStyle get() = ui(delegate.h0TextStyle)

    @get:Composable
    override val h1TextStyle: TextStyle get() = ui(delegate.h1TextStyle)

    @get:Composable
    override val h2TextStyle: TextStyle get() = ui(delegate.h2TextStyle)

    @get:Composable
    override val h3TextStyle: TextStyle get() = ui(delegate.h3TextStyle)

    @get:Composable
    override val h4TextStyle: TextStyle get() = ui(delegate.h4TextStyle)

    @get:Composable
    override val regular: TextStyle get() = ui(delegate.regular)

    @get:Composable
    override val medium: TextStyle get() = ui(delegate.medium)

    @get:Composable
    override val small: TextStyle get() = ui(delegate.small)

    @get:Composable
    override val editorTextStyle: TextStyle get() = mono(delegate.editorTextStyle)

    @get:Composable
    override val consoleTextStyle: TextStyle get() = mono(delegate.consoleTextStyle)

    private fun ui(style: TextStyle): TextStyle =
        if (substituteUi) IslandFontFamilies.substituteInto(style, uiFont) else style

    private fun mono(style: TextStyle): TextStyle =
        if (substituteMono) {
            IslandFontFamilies.substituteInto(style, monoFont, IslandFontFamilies.MonoLineHeightMultiplier)
        } else {
            style
        }
}
