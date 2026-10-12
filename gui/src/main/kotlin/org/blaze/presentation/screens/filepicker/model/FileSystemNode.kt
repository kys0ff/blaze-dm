package org.blaze.presentation.screens.filepicker.model

import java.nio.file.Path

data class FileSystemNode(
    val path: Path,
    val isDirectory: Boolean,
    val isHidden: Boolean
) {
    val displayName: String get() = path.fileName?.toString() ?: path.toString()
}
