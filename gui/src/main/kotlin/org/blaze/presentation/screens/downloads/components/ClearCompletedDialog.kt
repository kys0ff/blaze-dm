package org.blaze.presentation.screens.downloads.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BaseDialog
import org.blaze.presentation.components.StandardDialogButtons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * The rows a pending "clear completed" confirmation covers, captured the moment the button is
 * pressed so the dialog describes the action the user agreed to rather than a list that kept
 * changing underneath it. [seeding] is called out separately: those torrents are finished but still
 * sharing, and clearing stops them.
 */
data class ClearCompletedScope(val completed: Int, val seeding: Int) {
    /** Every entry the clear would drop. */
    val total: Int get() = completed + seeding
}

/**
 * Confirmation for clearing every finished download from the list in one action.
 *
 * Clearing is bulk and one-way for the list: the completion time, speeds and history of all those
 * rows disappear together, so the toolbar button does not act on its own. The dialog states the
 * scope as a count (a bare "clear all" reads very differently at 2 rows than at 200), names what it
 * deliberately does not touch — the files on disk — and warns when the sweep would also stop
 * torrents that are still seeding.
 */
@Composable
fun ClearCompletedDialog(
    scope: ClearCompletedScope,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = blazeStrings
    val cStrings = strings.downloads.dialogs.clearCompleted

    BaseDialog(onDismiss = onDismiss, width = 440.dp, title = cStrings.title) {
        Text(
            text = cStrings.message(scope.total),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = cStrings.detail,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
            color = JewelTheme.globalColors.text.info,
        )

        // Stopping a share is a real cost the "completed" label doesn't imply, so it gets the
        // louder treatment rather than sitting in the same grey hint as the reassurance above.
        if (scope.seeding > 0) {
            AdvisoryCallout(text = cStrings.seedingDetail(scope.seeding), tone = AdvisoryTone.WARNING)
        }

        StandardDialogButtons(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = cStrings.confirm,
            onConfirm = onConfirm
        )
    }
}
