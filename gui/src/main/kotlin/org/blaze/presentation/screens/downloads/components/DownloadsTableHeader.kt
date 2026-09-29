package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.DownloadsColumn
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.downloads.DownloadSortField
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.theme.BlazeTypography
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/** Which sort field a table column orders by when its header is clicked. */
private val DownloadsColumn.sortField: DownloadSortField
    get() = when (this) {
        DownloadsColumn.Name -> DownloadSortField.NAME
        DownloadsColumn.Progress -> DownloadSortField.PROGRESS
        DownloadsColumn.Size -> DownloadSortField.SIZE
        DownloadsColumn.Speed -> DownloadSortField.SPEED
        DownloadsColumn.Eta -> DownloadSortField.ETA
        DownloadsColumn.State -> DownloadSortField.STATE
    }

/** Header title, sourced from the localized column / sorting labels. */
@Composable
private fun DownloadsColumn.headerLabel(): String {
    val downloads = blazeStrings.downloads
    return when (this) {
        DownloadsColumn.Name -> downloads.columns.name
        DownloadsColumn.Progress -> downloads.columns.progress
        DownloadsColumn.Size -> downloads.columns.size
        DownloadsColumn.Speed -> downloads.columns.speed
        DownloadsColumn.Eta -> downloads.columns.eta
        DownloadsColumn.State -> downloads.columns.status
    }
}

/**
 * The download table's column header: one weighted, resizable, sortable cell per [DownloadsColumn]
 * followed by a trailing options (gear) slot, closed by a 1dp bottom hairline. There is no leading
 * select-all checkbox any more — selection is by row — so the header's fixed-width trailing gutter
 * mirrors the row's [OptionsGutterWidth] and every weighted column lines up with [DownloadRow]. The
 * active sort shows a [AllIconsKeys.General.ArrowUp] / [AllIconsKeys.General.ArrowDown] indicator,
 * and the right edge of each cell is a drag handle that re-weights that column.
 *
 * Each header cell aligns the *same way* as its column's cells: the numeric ([DownloadsColumn.endAligned])
 * columns right-align their header label to match the tabular, end-aligned row values.
 */
@Composable
fun DownloadsTableHeader(
    columnWidths: Map<String, Float>,
    sortField: DownloadSortField,
    sortAscending: Boolean,
    onSelectAll: () -> Unit,
    onSort: (DownloadSortField) -> Unit,
    onResize: (columnId: String, widthDp: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(BlazeLayout.TableHeaderHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadsColumn.entries.forEach { column ->
                HeaderCell(
                    column = column,
                    weight = column.weightFor(columnWidths),
                    sortedActive = column.sortField == sortField,
                    ascending = sortAscending,
                    onSort = { onSort(column.sortField) },
                    onResize = { widthDp -> onResize(column.id, widthDp) },
                )
            }
            HeaderOptionsSlot(onSelectAll = onSelectAll)
        }
        Divider(Orientation.Horizontal, modifier = Modifier.height(BlazeLayout.HeaderBorderWidth))
    }
}

@Composable
private fun RowScope.HeaderCell(
    column: DownloadsColumn,
    weight: Float,
    sortedActive: Boolean,
    ascending: Boolean,
    onSort: () -> Unit,
    onResize: (Float) -> Unit,
) {
    // A sorted column is marked by its arrow alone. The IDE never recolors a header label, and an
    // accent-blue "Name" competes with the selected row for the user's attention.
    val labelColor = JewelTheme.globalColors.text.normal
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()

    // A numeric column right-aligns its header so it sits over the end-aligned, tabular cells.
    val endAligned = column.endAligned

    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .hoverable(hoverInteraction)
            .background(if (hovered) BlazeColors.hover else Color.Transparent)
            .padding(start = CellStartPadding, end = CellEndPadding),
        contentAlignment = if (endAligned) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (endAligned) {
                Arrangement.spacedBy(4.dp, Alignment.End)
            } else {
                Arrangement.spacedBy(4.dp, Alignment.Start)
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSort),
        ) {
            Text(
                text = column.headerLabel(),
                style = BlazeTypography.tableHeader,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Only a start-aligned label absorbs the slack; an end-aligned one hugs the right edge.
                modifier = if (endAligned) Modifier else Modifier.weight(1f, fill = false),
            )
            if (sortedActive) {
                Icon(
                    key = if (ascending) AllIconsKeys.General.ArrowUp else AllIconsKeys.General.ArrowDown,
                    contentDescription = null,
                    // The arrow carries the sort state, and it does so in the secondary text color:
                    // state should be readable without being loud.
                    tint = JewelTheme.globalColors.text.info,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        // Right-edge drag handle: re-weights this column (bounded by its min width).
        val density = LocalDensity.current
        var lastWidth by remember(column.id, weight) { mutableStateOf(weight) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(6.dp)
                .fillMaxHeight()
                .pointerInput(column.id) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val deltaDp = with(density) { dragAmount.x.toDp() }.value
                        lastWidth = (lastWidth + deltaDp).coerceAtLeast(column.minWidth.value)
                        onResize(lastWidth)
                    }
                },
        )
    }
}

/**
 * The header's fixed trailing gutter that mirrors [OptionsGutterWidth], holding a gear that opens a
 * small options popup. With the select-all checkbox gone, "Select All" lives here (and in the row
 * context menu) so bulk selection stays discoverable.
 */
@Composable
private fun RowScope.HeaderOptionsSlot(onSelectAll: () -> Unit) { // TODO: Warning: Receiver parameter is never used
    val selection = blazeStrings.downloads.selection
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.width(OptionsGutterWidth).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        ToolbarIconButton(
            key = AllIconsKeys.General.Gear,
            tooltip = selection.selectAll,
            onClick = { expanded = true },
        )
        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                HeaderOptionsMenu(
                    onSelectAll = {
                        onSelectAll()
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A compact, themed menu card rendered inside the header options [Popup]. */
@Composable
private fun HeaderOptionsMenu(onSelectAll: () -> Unit) {
    val selection = blazeStrings.downloads.selection
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
            .padding(vertical = 4.dp),
    ) {
        MenuEntry(text = selection.selectAll, onClick = onSelectAll)
    }
}

@Composable
private fun MenuEntry(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) BlazeColors.hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = BlazeTypography.chip,
            color = JewelTheme.globalColors.text.normal,
            maxLines = 1,
        )
    }
}
