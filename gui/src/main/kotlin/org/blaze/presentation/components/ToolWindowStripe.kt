package org.blaze.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.ToggleableIconActionButton
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * A top-level view the tool-window stripe can switch to: an icon, a name for its tooltip, a stable
 * id, an optional badge count, and an optional accelerator shown in the tooltip in the IDE's
 * secondary color.
 */
data class SidebarItem(
    val label: String,
    val icon: IconKey,
    val id: String,
    val count: Int? = null,
    val shortcut: String? = null,
)

/**
 * The IDE's left tool-window stripe: a narrow, icon-only vertical gutter of view toggles. The
 * active view keeps the rounded selected-background highlight, every entry has a tooltip naming
 * it (plus its shortcut), and the whole strip is a flat panel separated from content by the
 * content's own divider. This replaces the old wide labeled sidebar so navigation reads like an
 * IDE, not a settings list.
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
fun ToolWindowStripe(
    items: List<SidebarItem>,
    selectedId: String,
    onItemSelected: (SidebarItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(BlazeLayout.ToolWindowStripeWidth)
            .fillMaxHeight()
            .background(JewelTheme.globalColors.panelBackground)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            ToggleableIconActionButton(
                key = item.icon,
                contentDescription = item.label,
                value = item.id == selectedId,
                onValueChange = { checked -> if (checked) onItemSelected(item) },
                iconModifier = Modifier.size(BlazeLayout.StripeIconSize),
                tooltip = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(item.label)
                        if (item.shortcut != null) {
                            Text(item.shortcut, color = JewelTheme.globalColors.text.info)
                        }
                    }
                },
            )
        }
    }
}
