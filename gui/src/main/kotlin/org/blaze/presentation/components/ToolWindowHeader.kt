package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/**
 * The modifier a floating tool window hands down so its pane's *own* header can drag the window.
 *
 * An IDE tool window is moved by its title bar, and in Blaze that title bar is the header the pane
 * draws itself ([org.blaze.presentation.screens.downloads.components.DownloadDetailsPanel],
 * [org.blaze.presentation.screens.serverlog.ServerLogPanel]) — the host must not draw a second one on
 * top of it. Providing the drag surface through a local keeps a single header per window and lets any
 * pane become a floating one without the pane knowing anything about floating. Null (the default) means
 * the header belongs to a docked pane, which has nowhere to move to.
 */
val LocalToolWindowDragHandle: ProvidableCompositionLocal<Modifier?> = staticCompositionLocalOf { null }

@Composable
fun ToolWindowHeader(
    title: String,
    actions: @Composable () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BlazeLayout.ToolWindowHeaderHeight)
                .background(JewelTheme.globalColors.panelBackground)
                .then(LocalToolWindowDragHandle.current ?: Modifier)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = JewelTheme.typography.medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                actions()
            }
        }
        Divider(Orientation.Horizontal)
    }
}
