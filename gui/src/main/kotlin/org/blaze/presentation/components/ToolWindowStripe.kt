package org.blaze.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.blaze.presentation.theme.BlazeIslandColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * A top-level view the tool-window stripe can switch to: an icon, a name for its tooltip, a stable
 * id, an optional badge count, an optional accelerator shown in the tooltip in the IDE's secondary
 * color, and [pinned] to anchor it to the bottom group (like the IDE's Settings gear) instead of the
 * top views group.
 */
data class SidebarItem(
    val label: String,
    val icon: IconKey,
    val id: String,
    val count: Int? = null,
    val shortcut: String? = null,
    val pinned: Boolean = false,
)

/**
 * The IDE's left tool-window stripe: a narrow, icon-only vertical gutter of view toggles that sits on
 * the window *frame*. The active view is marked by a subtle gray rounded background (never a
 * saturated accent fill), hover is an even subtler fill, and every entry carries a tooltip naming it
 * (plus its shortcut). Pinned items (Settings) anchor to the bottom, mirroring the IDE's bottom group.
 */
@Composable
fun ToolWindowStripe(
    items: List<SidebarItem>,
    selectedId: String,
    onItemSelected: (SidebarItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (top, bottom) = items.partition { !it.pinned }
    val stripeBackground = if (BlazeLayout.useIslands) BlazeIslandColors.frame else JewelTheme.globalColors.panelBackground

    Column(
        modifier = modifier
            .width(BlazeLayout.ToolWindowStripeWidth)
            .fillMaxHeight()
            .background(stripeBackground)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            top.forEach { item ->
                StripeToggleButton(
                    item = item,
                    selected = item.id == selectedId,
                    onClick = { onItemSelected(item) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            bottom.forEach { item ->
                StripeToggleButton(
                    item = item,
                    selected = item.id == selectedId,
                    onClick = { onItemSelected(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripeToggleButton(
    item: SidebarItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val background = when {
        selected -> BlazeIslandColors.stripeSelected
        pressed || hovered -> BlazeIslandColors.stripeHover
        else -> Color.Transparent
    }
    Tooltip(
        tooltip = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.label)
                if (item.shortcut != null) {
                    Text(item.shortcut, color = JewelTheme.globalColors.text.info)
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .size(BlazeLayout.StripeButtonHitTarget)
                .clip(RoundedCornerShape(BlazeLayout.StripeButtonRadius))
                .background(background)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                key = item.icon,
                contentDescription = item.label,
                modifier = Modifier.size(BlazeLayout.StripeIconSize),
            )
        }
    }
}
