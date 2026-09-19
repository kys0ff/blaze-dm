package org.blaze.presentation.screens.filepicker.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.filepicker.FilePickerEvent
import org.blaze.presentation.screens.filepicker.FilePickerState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun FilePickerPathField(
    state: FilePickerState,
    onEvent: (FilePickerEvent) -> Unit,
    strings: BlazeStrings
) {
    TextField(
        value = state.pathText,
        onValueChange = { onEvent(FilePickerEvent.PathChanged(it, strings)) },
        placeholder = { Text(strings.filePicker.pathPlaceholder) },
        outline = if (state.inputState.problem != null) Outline.Error else Outline.None,
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val pickable = state.pickable
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                    pickable != null
                ) {
                    onEvent(FilePickerEvent.Confirm(pickable))
                    true
                } else {
                    false
                }
            }
    )
}
