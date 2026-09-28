package org.blaze.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.blaze.theming.color.AccentSelector
import org.blaze.theming.color.DominantColorExtractor
import org.blaze.theming.color.DominantColorOptions
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's brand color, derived from the bundled icon instead of assumed.
 *
 * [DominantColorExtractor] (theming module) analyses `app-icon.png` once, off the UI thread,
 * and [AccentSelector] picks the vivid entry that reads as the brand rather than the most
 * frequent one (icon backgrounds and outlines dominate raw counts). The result is memoized
 * process-wide - a single icon needs a single extraction ever - and exposed as a [StateFlow]
 * so consumers recompose the moment it arrives.
 *
 * Until (and unless) extraction finishes, [color] is null; callers fall back to the palette
 * accent. A missing or undecodable resource logs and stays null forever: branding must never
 * break a window.
 */
internal object BrandIconAccent {

    /** Bundled with the app (and on the classpath in tests): src/main/resources/app-icon.png. */
    const val ICON_RESOURCE = "app-icon.png"

    private val logger = LoggerFactory.getLogger(BrandIconAccent::class.java)

    private val _color = MutableStateFlow<Color?>(null)
    val color: StateFlow<Color?> = _color.asStateFlow()

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Idempotent kick-off; the extraction runs exactly once per process. */
    fun ensureLoaded() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val result = runCatching {
                val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(ICON_RESOURCE)) {
                    "Brand icon missing from resources: $ICON_RESOURCE"
                }.readBytes()
                deriveAccent(bytes)
            }
            _color.value = result.getOrNull()
            result.onFailure { logger.info("Brand accent unavailable, using the theme accent: {}", it.message) }
        }
    }

    /**
     * Pure pipeline (bytes -> palette -> accent), separate from the coroutine plumbing so it
     * is directly testable. Null when the artwork holds no clear brand color.
     */
    internal fun deriveAccent(bytes: ByteArray): Color? {
        val palette = DominantColorExtractor.extract(bytes, OPTIONS)
        val argb = AccentSelector.selectArgb(palette) ?: return null
        // Color(Int) reads 0xAARRGGBB; the extractor always emits an opaque entry.
        return Color(argb)
    }

    private val OPTIONS = DominantColorOptions(
        // A couple more clusters than the default: the brand hue is often a minority color
        // inside an emblem, so give the selector more real candidates to choose from.
        colorCount = 6,
        // Sampling cost scales with sqrt(pixels); a 400x400 icon is fully covered anyway.
        maxSamples = (1 shl 15),
    )
}

/**
 * The icon-derived brand accent, or null while unavailable - see [BrandIconAccent].
 * Reading this is cheap: the first call schedules the one-off extraction, later calls no-op.
 */
@Composable
internal fun rememberBrandIconAccent(): Color? {
    BrandIconAccent.ensureLoaded()
    return BrandIconAccent.color.collectAsState().value
}
