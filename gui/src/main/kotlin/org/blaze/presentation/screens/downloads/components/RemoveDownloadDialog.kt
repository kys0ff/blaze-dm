package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.island.ui.dialog.IslandDialog
import org.island.ui.dialog.StandardDialogButtons
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
    onConfirm: (deleteFile: Boolean, setAsDefault: Boolean) -> Unit,
    /** Cost of removing this particular download (unfinished progress), or null when there is none. */
    notice: String? = null,
) {
    var deleteFromDisk by remember { mutableStateOf(false) }
    var setAsDefault by remember { mutableStateOf(false) }

    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs

    val secondary = JewelTheme.globalColors.text.info
    val hintStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    IslandDialog(onDismiss = onDismiss, width = 440.dp, title = dStrings.removeTitle) {
        // The name sits in the sentence in the same regular weight as the rest of it - the chrome is
        // regular-weight everywhere except dialog titles and group headers, and this is body text.
        Text(
            text = dStrings.removeMessage(downloadName),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        // Removing an unfinished transfer is not just a list edit: the partial file goes with it,
        // and for a source that cannot resume there is no way to get that work back.
        notice?.let { warning ->
            AdvisoryCallout(text = warning, tone = AdvisoryTone.WARNING)
        }

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

        StandardDialogButtons(
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
