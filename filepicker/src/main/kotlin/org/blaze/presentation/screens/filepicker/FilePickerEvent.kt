package org.blaze.presentation.screens.filepicker

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.i18n.BlazeStrings
import java.nio.file.Path

sealed interface FilePickerEvent {
    data class Init(
        val mode: org.blaze.presentation.screens.filepicker.model.FilePickerMode,
        val initialPath: Path?,
        val title: String?,
        val description: String?,
        val confirmText: String?,
        val fileFilter: (Path) -> Boolean,
        val strings: BlazeStrings
    ) : FilePickerEvent

    data class PathChanged(val value: TextFieldValue, val strings: BlazeStrings) : FilePickerEvent
    data class SelectPath(val path: Path) : FilePickerEvent
    data class ToggleDirectory(val path: Path) : FilePickerEvent
    data class ExpandDirectory(val path: Path) : FilePickerEvent
    data class CollapseDirectory(val path: Path) : FilePickerEvent
    
    data object MoveSelectionDown : FilePickerEvent
    data object MoveSelectionUp : FilePickerEvent
    data object MoveSelectionHome : FilePickerEvent
    data object MoveSelectionEnd : FilePickerEvent
    data object MoveSelectionRight : FilePickerEvent
    data object MoveSelectionLeft : FilePickerEvent
    
    data class Confirm(val path: Path) : FilePickerEvent
    data object Dismiss : FilePickerEvent
    
    data object GoHome : FilePickerEvent
    data object Refresh : FilePickerEvent
    data object ToggleHiddenFiles : FilePickerEvent
    
    data object ShowNewFolder : FilePickerEvent
    data object DismissNewFolder : FilePickerEvent
    data class NewFolderNameChanged(val value: TextFieldValue, val strings: BlazeStrings) : FilePickerEvent
    data class CreateNewFolder(val strings: BlazeStrings) : FilePickerEvent
}
