package org.blaze.presentation.screens.filepicker.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.components.IdeDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.blaze.presentation.screens.filepicker.FilePickerEvent
import org.blaze.presentation.screens.filepicker.FilePickerState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import java.nio.file.Path

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun NewFolderDialog(
    state: FilePickerState,
    parentPath: Path,
    onEvent: (FilePickerEvent) -> Unit,
    validateFolderName: (String, Path, BlazeStrings) -> String?,
    strings: BlazeStrings
) {
    val focusRequester = remember { FocusRequester() }
    val trimmed = state.newFolderName.text.trim()
    
    val problem = validateFolderName(trimmed, parentPath, strings)
    val message = state.newFolderCreationError ?: problem
    val canCreate = trimmed.isNotEmpty() && problem == null

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    IdeDialog(onDismiss = { onEvent(FilePickerEvent.DismissNewFolder) }, width = 420.dp, requestFocus = false) {
        IdeDialogTitle(strings.filePicker.newFolderTitle)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.filePicker.newFolderName)
            TextField(
                value = state.newFolderName,
                onValueChange = {
                    onEvent(FilePickerEvent.NewFolderNameChanged(it, strings))
                },
                outline = if (message != null) Outline.Error else Outline.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            onEvent(FilePickerEvent.CreateNewFolder(strings))
                            true
                        } else {
                            false
                        }
                    }
            )
            Text(
                text = message ?: strings.filePicker.newFolderCreatedIn(parentPath.toString()),
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = if (message != null) {
                    JewelTheme.globalColors.text.error
                } else {
                    JewelTheme.globalColors.text.info
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IdeDialogActions(
            dismissText = strings.common.cancel,
            onDismiss = { onEvent(FilePickerEvent.DismissNewFolder) },
            confirmText = strings.common.ok,
            onConfirm = { onEvent(FilePickerEvent.CreateNewFolder(strings)) },
            confirmEnabled = canCreate
        )
    }
}
