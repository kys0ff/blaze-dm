package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.IdeDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

@OptIn(ExperimentalJewelApi::class)
@Composable
fun FileConflictDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: (behavior: FileConflictBehavior) -> Unit
) {
    var selectedBehavior by remember { mutableStateOf(FileConflictBehavior.RENAME) }

    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs
    val sStrings = strings.settings

    IdeDialog(onDismiss = onDismiss, width = 440.dp) {
        IdeDialogTitle(dStrings.conflictTitle)

        Text(
            text = dStrings.conflictMessage(fileName),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioButtonRow(
                text = sStrings.renameOption,
                selected = selectedBehavior == FileConflictBehavior.RENAME,
                onClick = { selectedBehavior = FileConflictBehavior.RENAME },
                modifier = Modifier.fillMaxWidth()
            )

            RadioButtonRow(
                text = sStrings.overwriteOption,
                selected = selectedBehavior == FileConflictBehavior.OVERWRITE,
                onClick = { selectedBehavior = FileConflictBehavior.OVERWRITE },
                modifier = Modifier.fillMaxWidth()
            )

            RadioButtonRow(
                text = sStrings.skipOption,
                selected = selectedBehavior == FileConflictBehavior.SKIP,
                onClick = { selectedBehavior = FileConflictBehavior.SKIP },
                modifier = Modifier.fillMaxWidth()
            )
        }

        IdeDialogActions(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = strings.common.ok,
            onConfirm = {
                onConfirm(selectedBehavior)
                onDismiss()
            }
        )
    }
}
