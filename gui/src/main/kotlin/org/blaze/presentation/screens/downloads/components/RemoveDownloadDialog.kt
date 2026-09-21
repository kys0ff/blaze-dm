package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.IdeDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
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

    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs

    val secondary = JewelTheme.globalColors.text.info
    val hintStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    IdeDialog(onDismiss = onDismiss, width = 440.dp) {
        IdeDialogTitle(dStrings.removeTitle)

        val message = dStrings.removeMessage(downloadName)
        val nameIndex = message.indexOf(downloadName)
        Text(
            text = buildAnnotatedString {
                if (nameIndex != -1) {
                    append(message.substring(0, nameIndex))
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(downloadName)
                    }
                    append(message.substring(nameIndex + downloadName.length))
                } else {
                    append(message)
                }
            },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RadioButtonRow(
                    text = dStrings.removeOnly,
                    selected = !deleteFromDisk,
                    onClick = { deleteFromDisk = false },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = dStrings.removeOnlyHint,
                    style = hintStyle,
                    color = secondary,
                    modifier = Modifier.padding(start = 27.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RadioButtonRow(
                    text = dStrings.removeAndDelete,
                    selected = deleteFromDisk,
                    onClick = { deleteFromDisk = true },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = dStrings.removeAndDeleteHint,
                    style = hintStyle,
                    color = if (deleteFromDisk) JewelTheme.globalColors.text.error else secondary,
                    modifier = Modifier.padding(start = 27.dp)
                )
            }
        }

        IdeDialogActions(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = if (deleteFromDisk) dStrings.deleteAction else dStrings.removeAction,
            onConfirm = {
                onConfirm(deleteFromDisk, setAsDefault)
                onDismiss()
            },
            leading = {
                CheckboxRow(
                    text = dStrings.dontAskAgain,
                    checked = setAsDefault,
                    onCheckedChange = { setAsDefault = it }
                )
            }
        )
    }
}
