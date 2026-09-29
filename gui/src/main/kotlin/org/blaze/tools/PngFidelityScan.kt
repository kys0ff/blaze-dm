package org.blaze.tools

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Pixel-level chrome checks over the PNGs the screenshot harness renders.
 *
 * Both rules exist because a stray hairline or a leaked surface color is invisible to a compile, a
 * unit test and the eye on a small preview, but is exactly what makes the chrome stop reading as the
 * IDE: a `#eeeeee` line across the dark title-bar seam, or one panel painted with the frame color
 * inside an island.
 *
 * [StrayLine] — a 1–2px tall band that spans (nearly) the whole width, whose color differs sharply
 * from the identical band above and below it. A hairline that is *meant* to be there (the grid line
 * under the column header) is allowed by naming its exact color in [lineWhitelist].
 *
 * [MixedSurface] — inside a tall band of one surface color, a second color that covers a large share
 * of the band and is not a known token of that surface: the signature of one region painting itself
 * with another region's color.
 */
internal object PngFidelityScan {

    /** Luma gap (0..255 scale) that makes a hairline "harsh" rather than a subtle grid line. */
    private const val LineLumaDelta = 24f

    /** Minimum share of a row that must be the hairline color for it to span the window. */
    private const val RowCoverage = 0.9f

    /** How thin a full-width band must be to count as a hairline rather than a real region. */
    private const val MaxHairlineHeight = 2

    /** Share of a band a second color must own before it counts as a competing surface. */
    private const val SurfaceCoverage = 0.04f

    /** How wide a color must stretch, as a share of the image, to be a surface rather than content. */
    private const val MinFlatRunFraction = 0.30f

    /** Smallest band (in rows) worth checking for surface uniformity. */
    private const val MinBandHeight = 12

    private const val SurfaceLumaDelta = 6f

    /** How far two rows may sit from each other per channel and still be "the same band". */
    private const val BandTolerance = 10

    /**
     * Intentional hairlines, keyed by shot name to the exact colors allowed to span it. Anything not
     * listed here fails the build, so a new line has to be *chosen* rather than shipped by accident.
     */
    private val lineWhitelist: Map<String, Set<String>> = mapOf(
        // The IDE's table: the column header ends in a 1dp grid line, and rows separate by one too.
        "01-downloads-table" to setOf("#393b40", "#2b2d31", "#35373b"),
        "07-toolbar-filter" to setOf("#393b40", "#2b2d31", "#35373b"),
        "08-toolbar-selection" to setOf("#393b40", "#2b2d31", "#35373b"),
        "10-shell" to setOf("#393b40", "#2b2d31", "#35373b"),
        "13-shell-collapsed" to setOf("#393b40", "#2b2d31", "#35373b"),
        "11-settings-shell" to setOf("#393b40", "#2b2d31", "#35373b"),
    )

    /**
     * Colors that legitimately sit inside a surface: hairlines, rims, the selection tints and the
     * progress fill. The chrome *surfaces* themselves (island, frame) are deliberately NOT listed —
     * one showing up inside the other is exactly the leak this rule is for.
     */
    private val surfaceWhitelist: Set<String> = setOf(
        "#393b40", // island rim / selected stripe
        "#2b2d31", "#35373b", // hairlines
        "#2d5177", "#365273", "#4b6e92", // selection (active / inactive / hover)
        "#373328", "#f4ede0", // progress fill over the island (dark / light)
    )

    /**
     * Shots whose subject is a floating surface — a menu popup rendered over the window background.
     * Two surfaces in one frame is the whole point of those pictures, so [mixedSurfaces] could only
     * ever report the popup it is drawing; the stray-line rule still runs over them.
     */
    private val popupShots = setOf("09-menu-submenu")

    data class Issue(val shot: String, val rule: String, val detail: String)

    /** Runs both rules over one rendered PNG. */
    fun scan(png: File): List<Issue> {
        val shot = png.nameWithoutExtension.removeSuffix("-dark").removeSuffix("-light")
        val image = ImageIO.read(png) ?: return emptyList()
        return scan(image, shot, png.name)
    }

    fun scan(image: BufferedImage, shot: String, fileName: String): List<Issue> {
        val width = image.width
        val height = image.height
        val issues = mutableListOf<Issue>()
        issues += strayLines(image, shot, fileName)
        if (shot !in popupShots) {
            issues += mixedSurfaces(image, shot, fileName, width, height)
        }
        return issues
    }

    private fun strayLines(image: BufferedImage, shot: String, fileName: String): List<Issue> {
        val width = image.width
        val height = image.height
        // Per row: the color that owns it, how much of it it owns, and its luma.
        val dominant = arrayOfNulls<String>(height)
        val coverage = FloatArray(height)
        val luma = FloatArray(height)
        for (y in 0 until height) {
            val (color, share) = rowDominant(image, y, width)
            dominant[y] = color
            coverage[y] = share
            luma[y] = colorLuma(color)
        }

        val allowed = lineWhitelist[shot].orEmpty()
        val issues = mutableListOf<Issue>()
        var y = 0
        while (y < height) {
            val color = dominant[y] ?: break
            if (coverage[y] < RowCoverage) {
                y++
                continue
            }
            // Measure the vertical run of rows that this color owns end to end.
            var end = y
            while (end + 1 < height && dominant[end + 1] == color && coverage[end + 1] >= RowCoverage) end++
            val runHeight = end - y + 1
            val above = if (y > 0) dominant[y - 1] else null
            val below = if (end + 1 < height) dominant[end + 1] else null
            val hairline = runHeight <= MaxHairlineHeight
            // The bands it sits in must be the *same* surface (a gradient makes exact equality the
            // wrong test) and must differ sharply from the hairline itself.
            val sandwiched = above != null &&
                below != null &&
                sameBand(above, below) &&
                abs(luma[y] - colorLuma(above)) >= LineLumaDelta
            if (hairline && sandwiched && above != null) {
                val delta = abs(luma[y] - colorLuma(above))
                if (delta >= LineLumaDelta && color !in allowed) {
                    issues += Issue(
                        fileName,
                        "stray-line",
                        "y=$y full-width ${runHeight}px line $color over/under $above " +
                            "(Δluma ${delta.toInt()}); " +
                            "if it is intentional, add $color to the whitelist for '$shot'",
                    )
                }
            }
            y = end + 1
        }
        return issues
    }

    private fun mixedSurfaces(
        image: BufferedImage,
        shot: String,
        fileName: String,
        width: Int,
        height: Int,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        var y = 0
        while (y < height) {
            val (color, share) = rowDominant(image, y, width)
            if (share < 0.5f) {
                y++
                continue
            }
            var end = y
            while (end + 1 < height) {
                val next = rowDominant(image, end + 1, width)
                if (next.first != color || next.second < 0.5f) break
                end++
            }
            val bandHeight = end - y + 1
            if (bandHeight >= MinBandHeight) {
                val counts = HashMap<String, Int>()
                // Longest straight horizontal stretch of each color inside the band: only a color that
                // fills a wide, flat region is a competing *surface* — a progress bar, an icon or text
                // is never that wide, and glyph pixels are never that flat.
                val maxRun = HashMap<String, Int>()
                var total = 0
                for (row in y..end) {
                    var runColor = ""
                    var runLength = 0
                    for (x in 0 until width) {
                        val hex = rgbHex(image.getRGB(x, row))
                        counts.merge(hex, 1) { a, b -> a + b }
                        total++
                        if (hex == runColor) runLength++ else {
                            runColor = hex
                            runLength = 1
                        }
                        if ((maxRun[hex] ?: 0) < runLength) maxRun[hex] = runLength
                    }
                }
                val intruders = counts.entries
                    .filter { it.value.toFloat() / total >= SurfaceCoverage }
                    .filter { it.key != color }
                    .filter { (maxRun[it.key] ?: 0) >= MinFlatRunFraction * width }
                    .filter { abs(colorLuma(it.key) - colorLuma(color)) >= SurfaceLumaDelta }
                    .filter { it.key !in surfaceWhitelist }
                    .sortedByDescending { it.value }
                if (intruders.isNotEmpty()) {
                    issues += Issue(
                        fileName,
                        "mixed-surface",
                        "band y=$y..$end is $color but " +
                            intruders.take(3).joinToString {
                                "${it.key} (${100 * it.value / total}%, " +
                                    "${100 * (maxRun[it.key] ?: 0) / width}% wide)"
                            } +
                            " sits inside it as a second surface",
                    )
                }
            }
            y = end + 1
        }
        return issues
    }

    private fun rowDominant(image: BufferedImage, y: Int, width: Int): Pair<String, Float> {
        var bestHex = ""
        var bestCount = 0
        val counts = HashMap<String, Int>()
        for (x in 0 until width) {
            val hex = rgbHex(image.getRGB(x, y))
            val n = (counts[hex] ?: 0) + 1
            counts[hex] = n
            if (n > bestCount) {
                bestCount = n
                bestHex = hex
            }
        }
        return bestHex to bestCount.toFloat() / width
    }

    private fun rgbHex(rgb: Int): String = String.format("#%06x", rgb and 0xFFFFFF)

    /** True when two row colors are the same surface, allowing for a gradient's per-row drift. */
    private fun sameBand(a: String, b: String): Boolean {
        val channelsA = channels(a) ?: return a == b
        val channelsB = channels(b) ?: return a == b
        return channelsA.indices.all { abs(channelsA[it] - channelsB[it]) <= BandTolerance }
    }

    private fun channels(hex: String): IntArray? {
        if (hex.length != 7 || hex[0] != '#') return null
        return IntArray(3) { hex.substring(1 + it * 2, 3 + it * 2).toInt(16) }
    }

    private fun colorLuma(hex: String): Float {
        if (hex.length != 7 || hex[0] != '#') return 0f
        val r = hex.substring(1, 3).toInt(16)
        val g = hex.substring(3, 5).toInt(16)
        val b = hex.substring(5, 7).toInt(16)
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}

/** Formats the issues so a failing run says what to look at, then aborts the task. */
internal fun reportFidelityIssues(issues: List<PngFidelityScan.Issue>) {
    if (issues.isEmpty()) return
    println("[uiScreenshots] CHROME FIDELITY FAILURES (${issues.size}):")
    issues.forEach { println("  - ${it.shot} [${it.rule}] ${it.detail}") }
    error("${issues.size} chrome fidelity issue(s); see the list above")
}
