package org.blaze.presentation.screens.downloads.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.blaze.domain.models.Download
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BaseDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.blaze.presentation.screens.downloads.percentDone
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Text

/**
 * Confirmation for pausing a download the source cannot resume.
 *
 * Pausing is the one transport control that can destroy work: when the server proved it refuses
 * ranges, the bytes already on disk are discarded and the next start goes back to zero. The dialog
 * says so in those terms - the progress that is about to be lost - and leaves the decision to the
 * user instead of silently spending it. A "do not ask again" choice suppresses future confirmations
 * by turning off the [org.blaze.data.AppSettings.warnBeforeDiscardingProgress] preference.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun PauseRiskDialog(
    download: Download,
    onDismiss: () -> Unit,
    onConfirm: (doNotAskAgain: Boolean) -> Unit,
) {
    val strings = blazeStrings
    val risk = strings.downloads.dialogs.pauseRisk
    val percent = download.percentDone()
    var doNotAskAgain by remember { mutableStateOf(false) }

    BaseDialog(onDismiss = onDismiss, width = 440.dp) {
        IdeDialogTitle(risk.title)
        Text(text = risk.message(download.name))

        AdvisoryCallout(text = risk.detail(percent), tone = AdvisoryTone.WARNING)

        IdeDialogActions(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = risk.confirm,
            onConfirm = { onConfirm(doNotAskAgain) },
            leading = {
                CheckboxRow(
                    text = strings.downloads.dialogs.dontAskAgain,
                    checked = doNotAskAgain,
                    onCheckedChange = { doNotAskAgain = it },
                )
            },
        )
    }
}
