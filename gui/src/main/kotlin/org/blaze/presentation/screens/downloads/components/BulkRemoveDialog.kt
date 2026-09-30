package org.blaze.presentation.screens.downloads.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BaseDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Text

/**
 * Confirms a bulk "remove from list" before it runs. Removal is list-only (the downloaded files stay
 * on disk), but it touches many rows at once, so the count is stated and the user can opt out of the
 * prompt with "Don't ask again" — mirroring the single-row [RemoveDownloadDialog] affordance.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun BulkRemoveDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (doNotAskAgain: Boolean) -> Unit,
) {
    var doNotAskAgain by remember { mutableStateOf(false) }
    val strings = blazeStrings
    val selection = strings.downloads.selection
    val dialogs = strings.downloads.dialogs

    BaseDialog(onDismiss = onDismiss, width = 420.dp) {
        IdeDialogTitle(selection.bulkRemoveTitle)
        Text(text = selection.bulkRemoveMessage(count))

        IdeDialogActions(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = dialogs.removeAction,
            onConfirm = {
                onConfirm(doNotAskAgain)
                onDismiss()
            },
            leading = {
                CheckboxRow(
                    text = dialogs.dontAskAgain,
                    checked = doNotAskAgain,
                    onCheckedChange = { doNotAskAgain = it },
                )
            },
        )
    }
}
