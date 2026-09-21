package org.blaze.presentation.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * Blaze flame icon (flame only, transparent background).
 *
 * The download arrow is punched out of the flame using opposite path winding
 * (non-zero fill), so both the gradient fill and the facet clip respect it.
 *
 * NOTE: it is a multicolor icon, so don't let `Icon()` tint it:
 *   Icon(BlazeFlame, contentDescription = null, tint = Color.Unspecified)
 * or just use `Image(BlazeFlame, contentDescription = null)`.
 */
val BlazeFlame: ImageVector
    get() {
        _blazeFlame?.let { return it }

        val flame = "M64 114C86 114 100 98 100 78C100 58 84 44 72 12" +
            "C62 30 54 44 56 58C48 56 42 50 36 38C30 56 28 66 28 78C28 98 44 114 64 114Z"
        val arrowCutout = "M57 62H71V82H84L64 105L44 82H57Z"
        val flameWithCutout = addPathNodes("$flame $arrowCutout")

        val fire = Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to Color(0xFFFCF84A),
                0.30f to Color(0xFFF97A12),
                0.66f to Color(0xFFFE315D),
                1.00f to Color(0xFFB345F1),
            ),
            start = Offset(64f, 114f), // hot core at the bottom
            end = Offset(64f, 12f),    // cool tip at the top
        )

        return ImageVector.Builder(
            name = "BlazeFlame",
            defaultWidth = 128.dp,
            defaultHeight = 128.dp,
            viewportWidth = 128f,
            viewportHeight = 128f,
        ).apply {
            group(
                scaleX = 0.92f,
                scaleY = 0.92f,
                translationX = 5.12f,
                translationY = 5.12f,
            ) {
                // Flame body with the download arrow cut out
                addPath(pathData = flameWithCutout, fill = fire)

                // Geometric facets, clipped to the flame (and its cutout)
                group(clipPathData = flameWithCutout) {
                    addPath(
                        pathData = addPathNodes("M72 12L112 92L64 122L68 70Z"),
                        fill = SolidColor(Color.White),
                        fillAlpha = 0.14f,
                    )
                    addPath(
                        pathData = addPathNodes("M18 70L64 120L18 120Z"),
                        fill = SolidColor(Color.Black),
                        fillAlpha = 0.12f,
                    )
                }
            }
        }.build().also { _blazeFlame = it }
    }

private var _blazeFlame: ImageVector? = null