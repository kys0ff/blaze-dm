package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.DownloadsColumn
import org.blaze.presentation.screens.downloads.DownloadSortField
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
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
 * The download table's column header: a select-all slot followed by one weighted, resizable,
 * sortable cell per [DownloadsColumn], closed by a 1dp bottom hairline. Cells read the exact same
 * weights as [DownloadRow], so columns line up; the active sort shows a [AllIconsKeys.General.ArrowUp]
 * / [AllIconsKeys.General.ArrowDown] indicator (replacing the old sort chips), and the right edge of
 * each cell is a drag handle that re-weights that column.
 */
@Composable
fun DownloadsTableHeader(
    columnWidths: Map<String, Float>,
    sortField: DownloadSortField,
    sortAscending: Boolean,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onSort: (DownloadSortField) -> Unit,
    onResize: (columnId: String, widthDp: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(BlazeLayout.TableHeaderHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(SelectionCellWidth).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { onToggleSelectAll() },
                )
            }
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
    val accent = BlazeColors.accent
    val baseColor = JewelTheme.globalColors.text.normal
    val labelColor = if (sortedActive) accent else baseColor
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .hoverable(hoverInteraction)
            .background(if (hovered) BlazeColors.hover else Color.Transparent)
            .padding(start = CellStartPadding, end = CellEndPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSort),
        ) {
            Text(
                text = column.headerLabel(),
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Medium),
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (sortedActive) {
                Icon(
                    key = if (ascending) AllIconsKeys.General.ArrowUp else AllIconsKeys.General.ArrowDown,
                    contentDescription = null,
                    tint = accent,
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
