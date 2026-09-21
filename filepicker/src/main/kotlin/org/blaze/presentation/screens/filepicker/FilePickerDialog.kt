package org.blaze.presentation.screens.filepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.IdeDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.blaze.presentation.screens.filepicker.components.FilePickerPathField
import org.blaze.presentation.screens.filepicker.components.FilePickerToolbar
import org.blaze.presentation.screens.filepicker.components.FilePickerTree
import org.blaze.presentation.screens.filepicker.components.NewFolderDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalJewelApi::class)
@Composable
fun FilePickerDialog(
    onDismiss: () -> Unit,
    onPick: (Path) -> Unit,
    title: String? = null,
    description: String? = null,
    mode: FilePickerMode = FilePickerMode.Directory,
    initialPath: Path? = null,
    confirmText: String? = null,
    fileFilter: (Path) -> Boolean = { true },
) {
    val strings = blazeStrings
    val fStrings = strings.filePicker

    val actualTitle = title ?: if (mode == FilePickerMode.Directory) fStrings.titleFolder else fStrings.titleFile
    val actualConfirmText = confirmText ?: strings.common.ok

    val screenModel = org.koin.compose.koinInject<FilePickerScreenModel>()
    val state by screenModel.state.collectAsState()

    val treeFocus = remember { FocusRequester() }
    var scrollEffectTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        screenModel.onEvent(
            FilePickerEvent.Init(
                mode = mode,
                initialPath = initialPath,
                title = title,
                description = description,
                confirmText = confirmText,
                fileFilter = fileFilter,
                strings = strings
            )
        )
        treeFocus.requestFocus()
    }

    LaunchedEffect(Unit) {
        screenModel.effects.collect { effect ->
            when (effect) {
                is FilePickerEffect.Picked -> {
                    onPick(effect.path)
                    onDismiss()
                }
                FilePickerEffect.Dismiss -> onDismiss()
                is FilePickerEffect.ScrollToSelection -> {
                    scrollEffectTrigger++
                }
            }
        }
    }

    val secondary = JewelTheme.globalColors.text.info
    val errorColor = JewelTheme.globalColors.text.error
    val small = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    val directoriesOnly = state.mode == FilePickerMode.Directory
    val newFolderParent = remember(state.selected) {
        state.selected?.let {
            if (Files.isDirectory(it)) it else it.parent ?: it
        } ?: Path.of(System.getProperty("user.home"))
    }

    IdeDialog(onDismiss = onDismiss, width = 620.dp, requestFocus = false) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            IdeDialogTitle(actualTitle)
            val desc = state.description
            if (desc != null) {
                Text(text = desc, style = small, color = secondary)
            }
        }

        FilePickerToolbar(state = state, onEvent = screenModel::onEvent, strings = strings)

        FilePickerPathField(state = state, onEvent = screenModel::onEvent, strings = strings)

        FilePickerTree(
            state = state,
            onEvent = screenModel::onEvent,
            treeFocus = treeFocus,
            scrollEffectTrigger = scrollEffectTrigger,
            strings = strings
        )

        Text(
            text = state.inputState.problem
                ?: if (directoriesOnly) {
                    fStrings.hintFolder
                } else {
                    fStrings.hintFile
                },
            style = small,
            color = if (state.inputState.problem != null) errorColor else secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        IdeDialogActions(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = actualConfirmText,
            onConfirm = { state.pickable?.let { onPick(it); onDismiss() } },
            confirmEnabled = state.pickable != null
        )
    }

    if (state.showNewFolder) {
        NewFolderDialog(
            state = state,
            parentPath = newFolderParent,
            onEvent = screenModel::onEvent,
            validateFolderName = screenModel::validateFolderName,
            strings = strings
        )
    }
}
