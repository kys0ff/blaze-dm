package org.blaze.theming.color

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.SinglePixelPackedSampleModel
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Extracts the dominant colors of an image with a histogram + k-means-style refinement pass.
 *
 * This is the framework-agnostic half of the feature: it deals only in JDK [BufferedImage]
 * and packed ARGB [Int]s, never in Compose/Jewel types, keeping it consistent with the rest of
 * the theming module. The GUI layer decides how to *acquire* an image (classpath resource, an
 * in-memory bitmap) and how to turn the result into on-screen colors.
 *
 * All entry points are pure CPU work and thread-safe; call them from a background dispatcher
 * for large images.
 */
object DominantColorExtractor {

    private const val BITS = 5                       // bits kept per channel
    private const val SHIFT = 8 - BITS
    private const val BUCKETS = 1 shl (3 * BITS)     // 32768
    private const val OPAQUE = 0xFF shl 24

    /** Decodes [bytes] as an image (PNG/JPEG/GIF/BMP - whatever ImageIO supports) and extracts. */
    fun extract(
        bytes: ByteArray,
        options: DominantColorOptions = DominantColorOptions(),
    ): List<DominantColor> = extract(loadBufferedImage(bytes), options)

    fun extract(
        image: BufferedImage,
        options: DominantColorOptions = DominantColorOptions(),
    ): List<DominantColor> {
        val hist = buildHistogram(image, options) ?: return emptyList()
        return buildPalette(hist, options)
    }

    /**
     * Decodes an image from raw bytes.
     * @throws IOException if the format isn't supported or the data is corrupt.
     */
    fun loadBufferedImage(bytes: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IOException("Unsupported or corrupt image data")

    // ------------------------------------------------------------------
    // Histogram
    // ------------------------------------------------------------------

    private class Histogram {
        val counts = IntArray(BUCKETS)
        val sumR = LongArray(BUCKETS)
        val sumG = LongArray(BUCKETS)
        val sumB = LongArray(BUCKETS)
        var total = 0

        fun add(argb: Int, minAlpha: Int) {
            if ((argb ushr 24) < minAlpha) return
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val i = ((r shr SHIFT) shl (2 * BITS)) or ((g shr SHIFT) shl BITS) or (b shr SHIFT)
            counts[i]++
            // Keep the real channel sums so the final color is exact, not a bucket center.
            sumR[i] += r.toLong()
            sumG[i] += g.toLong()
            sumB[i] += b.toLong()
            total++
        }
    }

    private fun buildHistogram(image: BufferedImage, o: DominantColorOptions): Histogram? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null

        // Adaptive step: roughly maxSamples pixels regardless of image size.
        val step = max(1, sqrt(w.toDouble() * h / o.maxSamples).toInt())
        val hist = Histogram()
        val minAlpha = o.transparentAlpha

        val raster = image.raster
        val db = raster.dataBuffer
        val sm = raster.sampleModel
        val type = image.type

        if ((type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB) &&
            db is DataBufferInt && sm is SinglePixelPackedSampleModel
        ) {
            // Fast path: read the backing int[] directly (handles sub-images via offset/translate).
            val data = db.data
            val stride = sm.scanlineStride
            val base = db.offset - raster.sampleModelTranslateY * stride - raster.sampleModelTranslateX
            val forceOpaque = if (type == BufferedImage.TYPE_INT_RGB) OPAQUE else 0

            for (y in 0 until h step step) {
                val rowStart = base + y * stride
                for (x in 0 until w step step) {
                    hist.add(data[rowStart + x] or forceOpaque, minAlpha)
                }
            }
        } else {
            // Generic path: bulk-read one row at a time (much faster than per-pixel getRGB).
            val row = IntArray(w)
            for (y in 0 until h step step) {
                image.getRGB(0, y, w, 1, row, 0, w)
                for (x in 0 until w step step) {
                    hist.add(row[x], minAlpha)
                }
            }
        }

        return if (hist.total == 0) null else hist
    }

    // ------------------------------------------------------------------
    // Palette
    // ------------------------------------------------------------------

    private fun buildPalette(hist: Histogram, o: DominantColorOptions): List<DominantColor> {
        // 1) Compact the populated buckets, sorted by count descending.
        //    count and bucket index are packed into a Long so sorting needs no boxing.
        var n = 0
        for (i in 0 until BUCKETS) if (hist.counts[i] > 0) n++

        val keys = LongArray(n)
        var k = 0
        for (i in 0 until BUCKETS) {
            val c = hist.counts[i]
            if (c > 0) keys[k++] = (c.toLong() shl 32) or i.toLong()
        }
        keys.sort() // ascending; iterate from the end for descending counts

        val cnt = IntArray(n)
        val mr = DoubleArray(n)
        val mg = DoubleArray(n)
        val mb = DoubleArray(n)
        for (j in 0 until n) {
            val i = (keys[n - 1 - j] and 0xFFFFFFFFL).toInt()
            val c = hist.counts[i]
            cnt[j] = c
            mr[j] = hist.sumR[i].toDouble() / c
            mg[j] = hist.sumG[i].toDouble() / c
            mb[j] = hist.sumB[i].toDouble() / c
        }

        // 2) Greedy seeding: strongest colors first, skipping ones too close to a chosen seed.
        val minDistSq = o.minDistance * o.minDistance
        val cr = DoubleArray(o.colorCount)
        val cg = DoubleArray(o.colorCount)
        val cb = DoubleArray(o.colorCount)
        var clusters = 0

        for (j in 0 until n) {
            var far = true
            for (s in 0 until clusters) {
                if (distSq(mr[j], mg[j], mb[j], cr[s], cg[s], cb[s]) <= minDistSq) {
                    far = false
                    break
                }
            }
            if (far) {
                cr[clusters] = mr[j]
                cg[clusters] = mg[j]
                cb[clusters] = mb[j]
                clusters++
                if (clusters == o.colorCount) break
            }
        }

        // 3) Assign every bucket to its nearest centroid, recompute centroids, repeat.
        val clusterCount = IntArray(clusters)
        val sumR = DoubleArray(clusters)
        val sumG = DoubleArray(clusters)
        val sumB = DoubleArray(clusters)

        repeat(1 + o.refinementPasses) {
            clusterCount.fill(0)
            sumR.fill(0.0); sumG.fill(0.0); sumB.fill(0.0)

            for (j in 0 until n) {
                var best = 0
                var bestD = Double.MAX_VALUE
                for (s in 0 until clusters) {
                    val d = distSq(mr[j], mg[j], mb[j], cr[s], cg[s], cb[s])
                    if (d < bestD) {
                        bestD = d
                        best = s
                    }
                }
                val c = cnt[j]
                clusterCount[best] += c
                sumR[best] += mr[j] * c
                sumG[best] += mg[j] * c
                sumB[best] += mb[j] * c
            }

            for (s in 0 until clusters) {
                val c = clusterCount[s]
                if (c > 0) {
                    cr[s] = sumR[s] / c
                    cg[s] = sumG[s] / c
                    cb[s] = sumB[s] / c
                }
            }
        }

        // 4) Build the result.
        val total = hist.total.toFloat()
        return (0 until clusters)
            .filter { clusterCount[it] > 0 && clusterCount[it] / total >= o.minPercentage }
            .sortedByDescending { clusterCount[it] }
            .map { s ->
                val r = cr[s].roundToInt().coerceIn(0, 255)
                val g = cg[s].roundToInt().coerceIn(0, 255)
                val b = cb[s].roundToInt().coerceIn(0, 255)
                DominantColor(
                    argb = OPAQUE or (r shl 16) or (g shl 8) or b,
                    count = clusterCount[s],
                    percentage = clusterCount[s] / total,
                )
            }
    }

    /**
     * Squared "red-mean" weighted RGB distance (a cheap perceptual approximation).
     * Squared on purpose: no sqrt needed for comparisons.
     */
    private fun distSq(
        r1: Double, g1: Double, b1: Double,
        r2: Double, g2: Double, b2: Double,
    ): Double {
        val rMean = (r1 + r2) / 2.0
        val dr = r1 - r2
        val dg = g1 - g2
        val db = b1 - b2
        return (2.0 + rMean / 256.0) * dr * dr +
            4.0 * dg * dg +
            (2.0 + (255.0 - rMean) / 256.0) * db * db
    }
}
