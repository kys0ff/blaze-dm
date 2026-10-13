package org.island.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import org.island.foundation.DialogButton
import org.island.foundation.dialogButtonOrder
import org.island.theme.ui.IslandLayout
import org.island.ui.window.applyWindowsTitleBarTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun IslandDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 460.dp,
    height: Dp? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    requestFocus: Boolean = true,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val panelBackground = JewelTheme.globalColors.panelBackground

    var contentHeight by remember { mutableStateOf(0.dp) }

    val state = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(width, height ?: 230.dp),
    )

    if (requestFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = title.orEmpty(),
        resizable = false,
    ) {
        preventDialogMinimize()
        applyWindowsTitleBarTheme(panelBackground)

        LaunchedEffect(width, height, contentHeight) {
            if (height != null) {
                state.size = DpSize(width, height)
            } else if (contentHeight > 0.dp) {
                val decorations = (window.insets.top + window.insets.bottom).dp
                state.size = DpSize(width, contentHeight + decorations)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(JewelTheme.globalColors.panelBackground)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.Escape
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusable(),
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .wrapContentHeight(
                        align = Alignment.Top,
                        unbounded = true,
                    )
                    .onSizeChanged { size ->
                        val newHeight = with(density) {
                            size.height.toDp()
                        }
                        if (newHeight != contentHeight) {
                            contentHeight = newHeight
                        }
                    }
                    .padding(
                        horizontal = IslandLayout.DialogPaddingHorizontal,
                        vertical = IslandLayout.DialogPaddingVertical,
                    ),
                verticalArrangement = verticalArrangement,
                content = content,
            )
        }
    }
}

/**
 * Right-aligned dialog button bar: a secondary (dismiss) action followed by the default
 * (confirm) action. An optional [leading] slot (e.g. a "don't ask again" checkbox) is
 * pinned to the start of the row.
 */
@Composable
fun StandardDialogButtons(
    dismissText: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    leading: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        dialogButtonOrder(hasApply = false).forEach { button ->
            when (button) {
                DialogButton.Ok -> DefaultButton(
                    onClick = onConfirm,
                    enabled = confirmEnabled
                ) {
                    Text(confirmText)
                }

                DialogButton.Cancel -> OutlinedButton(onClick = onDismiss) {
                    Text(dismissText)
                }

                DialogButton.Apply -> Unit
            }
        }
    }
}
