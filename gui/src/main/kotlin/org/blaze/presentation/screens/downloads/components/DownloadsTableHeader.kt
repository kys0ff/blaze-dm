package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BlazeKeymap
import org.blaze.presentation.components.DownloadsColumn
import org.blaze.presentation.screens.downloads.DownloadSortField
import org.island.theme.ui.IslandLayout
import org.island.theme.ui.IslandSemanticColors
import org.island.theme.ui.IslandTypography
import org.island.ui.menu.IslandMenu
import org.island.ui.menu.MenuEntry
import org.island.ui.menu.rememberMenuAnchor
import org.island.ui.menu.trackingModifier
import org.island.ui.toolbar.IslandToolbarIconButton
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

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
 * The download table's column header: one weighted, resizable, sortable cell per visible column
 * followed by a trailing "more" slot holding the table options menu, closed by a 1dp bottom hairline.
 * There is no leading select-all checkbox any more — selection is by row — so the header's fixed-width
 * trailing gutter mirrors the row's [OptionsGutterWidth] and every weighted column lines up with
 * [DownloadRow]. The active sort shows a [AllIconsKeys.General.ArrowUp] /
 * [AllIconsKeys.General.ArrowDown] indicator, and the right edge of each cell is a drag handle that
 * re-weights that column.
 *
 * Each header cell aligns the *same way* as its column's cells: the numeric ([DownloadsColumn.endAligned])
 * columns right-align their header label to match the tabular, end-aligned row values.
 */
@Composable
fun DownloadsTableHeader(
    columnWidths: Map<String, Float>,
    columns: List<DownloadsColumn>,
    hiddenColumns: Set<String>,
    sortField: DownloadSortField,
    sortAscending: Boolean,
    /** True when every row of the current filter is selected; flips the menu's first item. */
    everythingSelected: Boolean,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onToggleColumn: (DownloadsColumn, Boolean) -> Unit,
    onResetColumnWidths: () -> Unit,
    onSort: (DownloadSortField) -> Unit,
    onResize: (columnId: String, widthDp: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IslandLayout.TableHeaderHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEach { column ->
                HeaderCell(
                    column = column,
                    weight = column.weightFor(columnWidths),
                    sortedActive = column.sortField == sortField,
                    ascending = sortAscending,
                    onSort = { onSort(column.sortField) },
                    onResize = { widthDp -> onResize(column.id, widthDp) },
                )
            }
            HeaderOptionsSlot(
                hiddenColumns = hiddenColumns,
                everythingSelected = everythingSelected,
                onSelectAll = onSelectAll,
                onDeselectAll = onDeselectAll,
                onToggleColumn = onToggleColumn,
                onResetColumnWidths = onResetColumnWidths,
            )
        }
        Divider(Orientation.Horizontal, modifier = Modifier.height(IslandLayout.HeaderBorderWidth))
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
            .background(if (hovered) IslandSemanticColors.hover else Color.Transparent)
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
                style = IslandTypography.tableHeader,
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
 * The header's fixed trailing gutter, mirroring [OptionsGutterWidth], holding the IDE's vertical
 * "more" glyph — a gear suggested *settings*, and this menu only configures the table.
 *
 * The menu itself is the table's option surface: [Select All] (which flips to Deselect once the whole
 * current filter is selected, so it never looks dead), a Columns submenu that shows or hides each
 * column, and Reset column widths. A Compact Rows item is deliberately absent — the table already
 * renders at the IDE's compact row height ([IslandLayout.RowHeight] = 24dp), and going below that
 * clips the row's icon.
 */
@Composable
private fun HeaderOptionsSlot( // The gutter is a fixed-width Box, so it needs no RowScope weighting
    hiddenColumns: Set<String>,
    everythingSelected: Boolean,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onToggleColumn: (DownloadsColumn, Boolean) -> Unit,
    onResetColumnWidths: () -> Unit,
) {
    val strings = blazeStrings
    val options = strings.downloads.tableOptions
    val selection = strings.downloads.selection
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberMenuAnchor()

    Box(
        modifier = Modifier.width(OptionsGutterWidth).fillMaxHeight().then(anchor.trackingModifier),
        contentAlignment = Alignment.Center,
    ) {
        IslandToolbarIconButton(
            key = AllIconsKeys.Actions.More,
            tooltip = options.menuTooltip,
            onClick = { expanded = true },
        )
        if (expanded) {
            // [alignRootToEnd] keeps the menu's right edge on the button that opened it, which is where
            // the header's options have always appeared; the row itself never hangs off the gutter.
            IslandMenu(
                entries = buildList {
                    add(
                        MenuEntry.Action(
                            label = if (everythingSelected) selection.deselect else selection.selectAll,
                            icon = if (everythingSelected) {
                                AllIconsKeys.Actions.Unselectall
                            } else {
                                AllIconsKeys.Actions.Selectall
                            },
                            // Only the selecting half has an accelerator; "deselect" is not a thing the
                            // keyboard does, and a row must not print a chord nothing handles.
                            accelerator =
                                if (everythingSelected) {
                                    null
                                } else {
                                    BlazeKeymap.displayValue(BlazeKeymap.Action.SelectAll)
                                },
                            onClick = {
                                if (everythingSelected) onDeselectAll() else onSelectAll()
                            },
                        ),
                    )
                    add(MenuEntry.Separator)
                    add(
                        MenuEntry.Submenu(
                            label = options.columns,
                            // Every column is listed, hidden ones included — otherwise a hidden column
                            // could never be brought back. Name stays marked and inert: it is the row's
                            // identity.
                            children = DownloadsColumn.entries.map { column ->
                                val isVisible = column.id !in hiddenColumns
                                val isName = column == DownloadsColumn.Name
                                MenuEntry.Action(
                                    label = column.headerLabel(),
                                    checked = isName || isVisible,
                                    enabled = !isName,
                                    onClick = { onToggleColumn(column, !isVisible) },
                                )
                            },
                        ),
                    )
                    add(MenuEntry.Separator)
                    add(
                        MenuEntry.Action(
                            label = options.resetColumnWidths,
                            icon = AllIconsKeys.General.Refresh,
                            onClick = onResetColumnWidths,
                        ),
                    )
                },
                anchor = anchor,
                onDismissRequest = { expanded = false },
                alignRootToEnd = true,
            )
        }
    }
}
