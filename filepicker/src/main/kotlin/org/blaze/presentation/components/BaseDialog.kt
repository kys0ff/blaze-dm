package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun BaseDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 460.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    requestFocus: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    var height by remember { mutableStateOf(230.dp) }
    val state = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        width = width,
        height = height,
    )

    // rememberDialogState treats its args as initial values only, so push updates in manually.
    LaunchedEffect(width, height) {
        state.size = DpSize(width, height)
    }

    if (requestFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        resizable = false,
    ) {
        Column(
            modifier = modifier
                .width(width)
                // Measure the content with unbounded height so it reports its natural size
                // instead of being clamped to the current window height.
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onSizeChanged { height = with(density) { it.height.toDp() + 20.dp } }
                .background(JewelTheme.globalColors.panelBackground)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusable()
                .padding(
                    horizontal = BlazeLayout.DialogPaddingHorizontal,
                    vertical = BlazeLayout.DialogPaddingVertical,
                ),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/** Standard dialog heading (15sp, semibold) used above the dialog body. */
@Composable
fun IdeDialogTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = JewelTheme.defaultTextStyle.copy(
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    )
}

/**
 * Right-aligned dialog button bar: a secondary (dismiss) action followed by the default
 * (confirm) action. An optional [leading] slot (e.g. a "don't ask again" checkbox) is
 * pinned to the start of the row.
 */
@Composable
fun IdeDialogActions(
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
        // The row follows the platform's own order off the centralized switch: OK then Cancel on
        // Windows/Linux, Cancel then OK on macOS. A plain dialog has no Apply, so the exhaustive
        // branch for it is intentionally empty.
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
