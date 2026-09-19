package org.blaze.presentation.screens.filepicker

import java.nio.file.Path

sealed interface FilePickerEffect {
    data class Picked(val path: Path) : FilePickerEffect
    data object Dismiss : FilePickerEffect
    data class ScrollToSelection(val index: Int) : FilePickerEffect
}
