package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Shared IDE-style dialog shell: a fixed-width panel with a subtle border, comfortable
 * padding, and Escape-to-dismiss. Every dialog in the app builds on this so surfaces stay
 * visually identical (corner radius, border, background, spacing).
 */
@Composable
fun IdeDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 460.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    requestFocus: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(8.dp)

    if (requestFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .width(width)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
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
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onDismiss) {
            Text(dismissText)
        }
        Spacer(Modifier.width(8.dp))
        DefaultButton(
            onClick = onConfirm,
            enabled = confirmEnabled
        ) {
            Text(confirmText)
        }
    }
}
