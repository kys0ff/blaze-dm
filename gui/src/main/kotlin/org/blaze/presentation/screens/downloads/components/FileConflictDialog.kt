package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.i18n.blazeStrings
import org.island.ui.dialog.IslandDialog
import org.island.ui.dialog.StandardDialogButtons
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

    IslandDialog(onDismiss = onDismiss, width = 440.dp, title = dStrings.conflictTitle) {
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

        StandardDialogButtons(
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
