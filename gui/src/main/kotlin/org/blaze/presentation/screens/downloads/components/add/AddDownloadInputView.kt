package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.theme.IdeColors
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import java.nio.file.Path

@OptIn(ExperimentalJewelApi::class)
@Composable
fun AddDownloadInputView(
    state: AddDownloadState,
    focusRequester: FocusRequester,
    askWhereToSave: Boolean,
    onFetch: () -> Unit
) {
    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = dStrings.downloadSource)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = state.url,
                    onValueChange = {
                        state.url = it
                        state.resetMetadata()
                    },
                    placeholder = { Text(dStrings.addUrlPlaceholder) },
                    outline = if (state.looksSupported) Outline.None else Outline.Warning,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                onFetch()
                                true
                            } else {
                                false
                            }
                        }
                )
                OutlinedButton(onClick = { state.showFilePicker = true }) {
                    Text(dStrings.addFromFile)
                }
            }

            if (!state.looksSupported) {
                Text(
                    text = dStrings.sourceWarning,
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                    color = IdeColors.warning
                )
            }
        }

        if (askWhereToSave) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = dStrings.saveTo)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = state.destination,
                        onValueChange = { state.destination = it },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { state.showFolderPicker = true }) {
                        Text(strings.common.browse)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = dStrings.scheduleDelayLabel)
            TextField(
                value = state.scheduleDelay,
                onValueChange = { state.scheduleDelay = it },
                placeholder = { Text(dStrings.scheduleDelayPlaceholder) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (state.showFolderPicker) {
        FilePickerDialog(
            onDismiss = { state.showFolderPicker = false },
            onPick = { path ->
                state.destination = TextFieldValue(path.toString())
                state.showFolderPicker = false
            },
            mode = FilePickerMode.Directory,
            initialPath = Path.of(state.destination.text).takeIf { it.toFile().exists() }
        )
    }

    if (state.showFilePicker) {
        FilePickerDialog(
            onDismiss = { state.showFilePicker = false },
            onPick = { path ->
                val file = path.toFile()
                state.resetMetadata()
                if (file.extension.lowercase() == "txt") {
                    val urls = file.readText().lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    
                    if (urls.size > 1) {
                        state.url = TextFieldValue(file.name)
                        state.batchItems.addAll(urls.map { BatchDownloadItem(it) })
                    } else if (urls.size == 1) {
                        state.url = TextFieldValue(urls[0])
                    }
                } else {
                    state.url = TextFieldValue(path.toString())
                }
                state.showFilePicker = false
            },
            mode = FilePickerMode.File,
            title = dStrings.selectSourceFile,
            fileFilter = { path ->
                val ext = path.toFile().extension.lowercase()
                ext == "torrent" || ext == "txt"
            }
        )
    }
}
