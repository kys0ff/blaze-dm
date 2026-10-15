package org.island.ui.notifications

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The clipboard seam island components need (currently: copying a notification's detail text).
 * Apps provide their platform implementation via [LocalIslandClipboard]; components never reach
 * for an OS clipboard directly.
 */
fun interface IslandClipboard {
    fun copy(text: String)
}

/**
 * CompositionLocal carrying the app's [IslandClipboard]. Null (the default) means no clipboard is
 * wired: copy affordances quietly do their best to exist anyway, they just cannot deliver.
 */
val LocalIslandClipboard: ProvidableCompositionLocal<IslandClipboard?> =
    staticCompositionLocalOf { null }
