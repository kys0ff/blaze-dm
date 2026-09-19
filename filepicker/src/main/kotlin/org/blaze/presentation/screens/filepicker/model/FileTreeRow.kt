package org.blaze.presentation.screens.filepicker.model

import org.blaze.presentation.screens.filepicker.model.FileSystemNode

data class FileTreeRow(
    val node: FileSystemNode,
    val depth: Int
)
