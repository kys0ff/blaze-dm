package org.island.ui.toolbar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.island.theme.ui.*

/**
 * A flat IDE-style toolbar icon button: a 16dp glyph in a compact square hit target, always with a
 * tooltip. An optional [shortcut] (from the platform keymap) is appended to the tooltip in the
 * theme's secondary color, exactly like IntelliJ's action tooltips.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IslandToolbarIconButton(
    key: IconKey,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shortcut: String? = null,
) {
    ActionButton(
        onClick = onClick,
        modifier = modifier.size(IslandLayout.ToolbarButtonHitTarget),
        enabled = enabled,
        tooltip = {
            if (shortcut != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tooltip)
                    Text(shortcut, color = JewelTheme.globalColors.text.info)
                }
            } else {
                Text(tooltip)
            }
        },
    ) {
        Icon(key, tooltip, modifier = Modifier.size(IslandLayout.ToolbarIconSize))
    }
}
