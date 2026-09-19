package org.blaze.presentation.screens.filepicker

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.presentation.screens.filepicker.model.FilePickerInputState
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.screens.filepicker.model.FileSystemNode
import org.blaze.presentation.screens.filepicker.model.FileTreeRow
import java.nio.file.Path

data class FilePickerState(
    val mode: FilePickerMode = FilePickerMode.Directory,
    val title: String? = null,
    val description: String? = null,
    val confirmText: String? = null,
    val fileFilter: (Path) -> Boolean = { true },
    
    val roots: List<FileSystemNode> = emptyList(),
    val expanded: Map<Path, Boolean> = emptyMap(),
    val children: Map<Path, List<FileSystemNode>> = emptyMap(),
    val selected: Path? = null,
    val showHidden: Boolean = false,
    val pathText: TextFieldValue = TextFieldValue(""),
    val inputState: FilePickerInputState = FilePickerInputState(null, exists = false, problem = null, isValid = false),
    
    val showNewFolder: Boolean = false,
    val newFolderName: TextFieldValue = TextFieldValue(""),
    val newFolderCreationError: String? = null
) {
    val pickable: Path? get() = if (inputState.isValid) inputState.path else null

    fun visibleRows(): List<FileTreeRow> {
        val out = ArrayList<FileTreeRow>()
        fun visit(node: FileSystemNode, depth: Int) {
            out += FileTreeRow(node, depth)
            if (expanded[node.path] == true) {
                children[node.path]?.forEach { child ->
                    if (showHidden || !child.isHidden) visit(child, depth + 1)
                }
            }
        }
        roots.forEach { visit(it, 0) }
        return out
    }
}
