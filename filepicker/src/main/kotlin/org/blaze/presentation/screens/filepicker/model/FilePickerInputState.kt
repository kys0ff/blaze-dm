package org.blaze.presentation.screens.filepicker.model

import java.nio.file.Path

data class FilePickerInputState(
    val path: Path?,
    val exists: Boolean,
    val problem: String?,
    val isValid: Boolean
)
