package org.blaze.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.LocalAmbientGlowEnabled
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.window.defaultTitleBarStyle

/**
 * The IDE's "ambient" chrome glow, measured off `docs/reference/ide-islands-dark.png` (a 1440px
 * wide window, base frame `#26282c`).
 *
 * The reference field is *separable*: inside the title-bar strip the tint is a purely horizontal
 * triangle — it climbs from zero at the left edge to its peak at x≈110 and falls back to zero at
 * x≈700, and every row of the strip reads the same (y=2 and y=40 differ only by the vertical
 * term). Down the left stripe the same horizontal profile decays linearly to nothing by y≈300.
 * That is why this is implemented as two overlapping linear gradients rather than one ellipse:
 * an ellipse centred on the corner cannot produce the dead column at x<10, and would overshoot
 * the measured pixels by ~20 units there.
 *
 * Because the second layer re-paints the *frame* color over the first, the composite is exactly
 * `frame ⊕ (glow * horizontalProfile * (1 - verticalProfile))` — it fades smoothly to precisely
 * the frame color at both the right edge and the bottom, with no hard edge and no banding, and it
 * is fully transparent wherever the profile is zero.
 */
object BlazeAmbientGradient {

    /** Where the glow peaks, as a fraction of the window width (measured 110px / 1440px). */
    const val PeakXFraction = 0.0764f

    /** Where the glow is back to the frame color, as a fraction of the width (measured ~700px). */
    const val EndXFraction = 0.4861f

    /** How far down the glow bleeds before it is fully back to the frame (measured ~300px). */
    val VerticalRadius = 280.dp

    /**
     * Blend of the accent into the frame at the glow's peak. The reference peak tint is
     * `#26282c -> (+5 R, +25 G, +22 B)` at x=110, i.e. a luminance lift of ~19 units; Blaze's accent
     * is the flame orange, so the same 19-unit lift needs a slightly larger share of a color that
     * carries less green. 15% (the top of the brief's 10-15% band) is what lands the app's peak
     * luminance delta on the reference's - see the gradient landmarks in `scripts/ui_delta_check.py`,
     * which compare shape (Δluminance over the sampled base) rather than hue.
     */
    const val DarkAlpha = 0.15f
    const val LightAlpha = 0.08f

    /** The accent color the glow is tinted with: the brand flame, falling back to the theme accent. */
    @Composable
    fun ambientAccent(): Color = rememberBrandIconAccent() ?: BlazeColors.accent
}

/** Whether the glow is drawn at all: the structural switch and the user preference must agree. */
@Composable
internal fun ambientGlowEnabled(): Boolean =
    BlazeLayout.USE_AMBIENT_GRADIENT && LocalAmbientGlowEnabled.current

/**
 * The peak tint: [base] blended a little of the way toward [accent]. Pure so the math can be
 * checked without a composition, and so a palette can never push the result off the base-to-accent
 * line (which is what keeps the glow a tint rather than a new surface).
 */
internal fun ambientTintColor(base: Color, accent: Color, isDark: Boolean): Color {
    val fraction = if (isDark) BlazeAmbientGradient.DarkAlpha else BlazeAmbientGradient.LightAlpha
    return Color(
        red = base.red + (accent.red - base.red) * fraction,
        green = base.green + (accent.green - base.green) * fraction,
        blue = base.blue + (accent.blue - base.blue) * fraction,
        alpha = base.alpha,
    )
}

/**
 * Paints the window *frame* layer: the flat frame color with the ambient glow anchored at the
 * window's top-left corner, behind the title bar, the tool-window stripe and the islands.
 *
 * [anchorY] is how far this node sits below the window's top edge, so the glow stays one continuous
 * field across the title bar (`anchorY = 0`) and the body below it. Everything opaque drawn on top
 * — the islands — hides it by itself; the gaps, the stripe and the bar are what show it.
 *
 * [bleedTop] extends the painting *above* the node's own top edge by that much, sampling the same
 * field. It exists for the 1dp hairline row Jewel puts under the title bar: that row is a sibling of
 * this layer, and in Islands its own border color is transparent, so an unpainted pixel there shows
 * whatever the AWT window paints underneath — a light-theme-colored line across the dark glow.
 *
 * The glow only *draws*; it installs no pointer input, so title-bar hit-testing and dragging are
 * untouched, and the alpha is low enough that bar text keeps its contrast.
 */
@Composable
fun Modifier.blazeAmbientFrame(anchorY: Dp = 0.dp, bleedTop: Dp = 0.dp): Modifier {
    val base = BlazeIslandColors.frame
    val accent = BlazeAmbientGradient.ambientAccent()
    val tint = ambientTintColor(base, accent, JewelTheme.isDark)
    val enabled = ambientGlowEnabled()
    val verticalRadius = BlazeAmbientGradient.VerticalRadius

    return drawBehind {
        // One rect covering the node plus any bleed above it: every layer below paints into it, so
        // the bleed can never show as a band of its own.
        val bleedPx = bleedTop.toPx()
        val area = Size(size.width, size.height + bleedPx)
        val origin = Offset(0f, -bleedPx)
        drawRect(base, topLeft = origin, size = area)
        if (!enabled || size.width <= 0f) return@drawBehind

        val top = -anchorY.toPx()
        val verticalPx = verticalRadius.toPx()
        // Alpha-only stops: keeping each layer's RGB fixed along its ramp means only the coverage
        // animates, so the glow can never pick up a grey mid-tone and the erase can never overshoot.
        val glowOff = tint.copy(alpha = 0f)
        val baseOff = base.copy(alpha = 0f)

        // Horizontal triangle: zero at the left edge, the full tint at the peak, back to zero.
        // RGB stays constant across the stops so only alpha ramps — no darkening in the middle.
        drawRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to glowOff,
                    BlazeAmbientGradient.PeakXFraction to tint,
                    BlazeAmbientGradient.EndXFraction to glowOff,
                ),
                start = Offset.Zero,
                end = Offset(size.width, 0f),
            ),
            topLeft = origin,
            size = area,
        )

        // Vertical decay: the frame color fades back in over `VerticalRadius`, erasing the glow.
        drawRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(0f to baseOff, 1f to base),
                start = Offset(0f, top),
                end = Offset(0f, top + verticalPx),
            ),
            topLeft = origin,
            size = area,
        )
    }
}

/**
 * The body's frame layer: the same glow field as the title bar above it, anchored so the two read
 * as one continuous field, and bleeding up over the title bar's transparent 1dp border row so that
 * row is painted chrome rather than the window's raw background.
 */
@Composable
fun Modifier.blazeAmbientBodyFrame(): Modifier {
    val bleed = if (BlazeLayout.USE_ISLANDS) BlazeLayout.TitleBarBorderHeight else 0.dp
    return blazeAmbientFrame(anchorY = ambientBodyAnchorY(), bleedTop = bleed)
}

/**
 * Distance from the window's top edge to the top of the body: the title bar's own height plus the
 * 1dp border Jewel places under it. Read from the very style the bar renders with, so the glow's
 * anchor can never drift away from where the bar actually ends.
 */
@Composable
@ReadOnlyComposable
internal fun ambientBodyAnchorY(): Dp = JewelTheme.defaultTitleBarStyle.metrics.height + 1.dp
