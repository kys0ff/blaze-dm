package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

data class SidebarItem(
    val label: String,
    val icon: IconKey,
    val id: String,
    val count: Int? = null
)

private val ListItemShape = RoundedCornerShape(6.dp)

@Composable
fun Sidebar(
    items: List<SidebarItem>,
    selectedItem: SidebarItem,
    onItemSelected: (SidebarItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = blazeStrings
    Row(
        modifier = modifier
            .width(200.dp)
            .fillMaxHeight()
            .background(JewelTheme.globalColors.panelBackground)
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ToolWindowHeader(title = strings.common.views)

            Column(
                modifier = Modifier.padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items.forEach { item ->
                    SidebarRow(
                        item = item,
                        isSelected = item.id == selectedItem.id,
                        onClick = { onItemSelected(item) }
                    )
                }
            }
        }
        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
    }
}

@Composable
private fun SidebarRow(
    item: SidebarItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val style = JewelTheme.simpleListItemStyle
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        isSelected -> style.colors.backgroundSelectedActive
        hovered -> BlazeColors.hover
        else -> Color.Transparent
    }
    val contentColor = if (isSelected) style.colors.contentSelectedActive else Color.Unspecified
    val countColor = if (isSelected) style.colors.contentSelectedActive else JewelTheme.globalColors.text.info

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(ListItemShape)
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                key = item.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor
            )
            Text(
                text = item.label,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            item.count?.let { count ->
                Text(
                    text = count.toString(),
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                    color = countColor
                )
            }
        }
    }
}
