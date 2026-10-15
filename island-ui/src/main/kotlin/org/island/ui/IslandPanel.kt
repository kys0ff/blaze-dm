package org.island.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.island.theme.ui.IslandChromeColors
import org.island.theme.ui.IslandLayout

/**
 * One "island" of the New UI shell: a panel that floats on the window frame as a rounded rect of
 * the island color, clipping its content. No elevation/shadow — the frame background and the
 * surrounding gap (see [IslandLayout.IslandGap]) do the separation work, exactly like the reference IDE.
 *
 * A 1dp rim is opt-in via [border]; the reference islands read as borderless, so it defaults off.
 */
@Composable
fun IslandPanel(
    modifier: Modifier = Modifier,
    border: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(IslandLayout.IslandRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(IslandChromeColors.island)
            .then(
                if (border) {
                    Modifier.border(IslandLayout.IslandBorderWidth, IslandChromeColors.islandBorder, shape)
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}
