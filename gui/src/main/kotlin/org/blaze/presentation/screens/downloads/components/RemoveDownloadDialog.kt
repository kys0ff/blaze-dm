package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

@OptIn(ExperimentalJewelApi::class)
@Composable
fun RemoveDownloadDialog(
    downloadName: String,
    onDismiss: () -> Unit,
    onConfirm: (deleteFile: Boolean, setAsDefault: Boolean) -> Unit
) {
    var deleteFromDisk by remember { mutableStateOf(false) }
    var setAsDefault by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val shape = RoundedCornerShape(8.dp)
    val secondary = JewelTheme.globalColors.text.info
    val hintStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Remove download",
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )

            Text(
                text = buildAnnotatedString {
                    append("Remove ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(downloadName) }
                    append("?")
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RadioButtonRow(
                        text = "Remove from list only",
                        selected = !deleteFromDisk,
                        onClick = { deleteFromDisk = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "The downloaded file stays on disk.",
                        style = hintStyle,
                        color = secondary,
                        modifier = Modifier.padding(start = 27.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RadioButtonRow(
                        text = "Remove from list and delete file from disk",
                        selected = deleteFromDisk,
                        onClick = { deleteFromDisk = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "The file is permanently deleted and can't be restored.",
                        style = hintStyle,
                        color = if (deleteFromDisk) JewelTheme.globalColors.text.error else secondary,
                        modifier = Modifier.padding(start = 27.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CheckboxRow(
                    text = "Don't ask again",
                    checked = setAsDefault,
                    onCheckedChange = { setAsDefault = it }
                )

                Spacer(Modifier.weight(1f))

                OutlinedButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                DefaultButton(
                    onClick = {
                        onConfirm(deleteFromDisk, setAsDefault)
                        onDismiss()
                    }
                ) {
                    Text(if (deleteFromDisk) "Delete" else "Remove")
                }
            }
        }
    }
}
