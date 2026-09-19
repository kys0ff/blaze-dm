package org.blaze.presentation.screens.filepicker.components

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.filepicker.FilePickerEvent
import org.blaze.presentation.screens.filepicker.FilePickerState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun FilePickerTree(
    state: FilePickerState,
    onEvent: (FilePickerEvent) -> Unit,
    treeFocus: FocusRequester,
    scrollEffectTrigger: Int?,
    strings: BlazeStrings
) {
    val listState = rememberLazyListState()
    var treeFocused by remember { mutableStateOf(false) }
    val rows = state.visibleRows()

    LaunchedEffect(state.selected, scrollEffectTrigger) {
        val index = rows.indexOfFirst { it.node.path == state.selected }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        when {
            visible.isEmpty() -> listState.scrollToItem((index - 3).coerceAtLeast(0))
            index <= visible.first().index -> listState.scrollToItem(index)
            index >= visible.last().index ->
                listState.scrollToItem((index - visible.size + 2).coerceAtLeast(0))
        }
    }

    val onTreeKey: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown || rows.isEmpty()) return@handler false
        when (event.key) {
            Key.DirectionDown -> {
                onEvent(FilePickerEvent.MoveSelectionDown)
                true
            }
            Key.DirectionUp -> {
                onEvent(FilePickerEvent.MoveSelectionUp)
                true
            }
            Key.MoveHome -> {
                onEvent(FilePickerEvent.MoveSelectionHome)
                true
            }
            Key.MoveEnd -> {
                onEvent(FilePickerEvent.MoveSelectionEnd)
                true
            }
            Key.DirectionRight -> {
                onEvent(FilePickerEvent.MoveSelectionRight)
                true
            }
            Key.DirectionLeft -> {
                onEvent(FilePickerEvent.MoveSelectionLeft)
                true
            }
            Key.Enter, Key.NumPadEnter -> {
                val pickable = state.pickable
                if (pickable != null) {
                    onEvent(FilePickerEvent.Confirm(pickable))
                } else {
                    val index = rows.indexOfFirst { it.node.path == state.selected }
                    rows.getOrNull(index)?.takeIf { it.node.isDirectory }?.let {
                        onEvent(FilePickerEvent.ToggleDirectory(it.node.path))
                    }
                }
                true
            }
            else -> false
        }
    }

    val treeShape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .border(1.dp, JewelTheme.globalColors.borders.normal, treeShape)
            .clip(treeShape)
            .focusRequester(treeFocus)
            .onFocusChanged { treeFocused = it.hasFocus }
            .onPreviewKeyEvent(onTreeKey)
            .focusable()
    ) {
        VerticallyScrollableContainer(listState, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(rows, key = { it.node.path }) { row ->
                    FilePickerTreeRow(
                        row = row,
                        isExpanded = state.expanded[row.node.path] == true,
                        isSelected = row.node.path == state.selected,
                        treeFocused = treeFocused,
                        onSelect = {
                            onEvent(FilePickerEvent.SelectPath(row.node.path))
                            treeFocus.requestFocus()
                        },
                        onToggle = {
                            onEvent(FilePickerEvent.ToggleDirectory(row.node.path))
                            treeFocus.requestFocus()
                        },
                        onDoubleClick = {
                            if (row.node.isDirectory) {
                                onEvent(FilePickerEvent.ToggleDirectory(row.node.path))
                            } else {
                                onEvent(FilePickerEvent.Confirm(row.node.path))
                            }
                        },
                        strings = strings
                    )
                }
            }
        }
    }
}
