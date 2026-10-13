package org.blaze.presentation.screens.filepicker

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.filepicker.components.FilePickerPathField
import org.blaze.presentation.screens.filepicker.components.FilePickerToolbar
import org.blaze.presentation.screens.filepicker.components.FilePickerTree
import org.blaze.presentation.screens.filepicker.components.NewFolderDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerContext
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.island.ui.dialog.IslandDialog
import org.island.ui.dialog.StandardDialogButtons
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
    /**
     * Which dialog this is, and therefore which remembered directory it opens at and updates. Required:
     * a picker with no identity would share everyone else's memory, and "the extension folder" would
     * leak into "where do my downloads go".
     */
    context: FilePickerContext,
    initialPath: Path? = null,
    confirmText: String? = null,
    fileFilter: (Path) -> Boolean = { true },
    /**
     * File extensions the caller allows picking (e.g. `["torrent", "txt"]`). When non-empty
     * and in [FilePickerMode.File], the dialog shows them as a "Supported types:" hint so the
     * user knows up front what's selectable. This is display-only; [fileFilter] still decides
     * what's actually accepted.
     */
    supportedExtensions: List<String> = emptyList(),
) {
    val strings = blazeStrings
    val fStrings = strings.filePicker

    val actualTitle =
        title ?: if (mode == FilePickerMode.Directory) fStrings.titleFolder else fStrings.titleFile
    val actualConfirmText = confirmText ?: strings.common.ok

    val screenModel = org.koin.compose.koinInject<FilePickerScreenModel>()
    val state by screenModel.state.collectAsState()

    val treeFocus = remember { FocusRequester() }
    var scrollEffectTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        screenModel.onEvent(
            FilePickerEvent.Init(
                mode = mode,
                context = context,
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

    IslandDialog(
        onDismiss = onDismiss,
        height = 520.dp,
        width = 620.dp,
        requestFocus = false,
        title = actualTitle
    ) {
        // The title lives in the window caption (single source of truth), so the content opens with
        // the optional description rather than repeating the heading.
        state.description?.let { desc ->
            Text(text = desc, style = small, color = secondary)
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

        val supportedLabel = if (!directoriesOnly && supportedExtensions.isNotEmpty()) {
            fStrings.supportedTypes(
                supportedExtensions.joinToString(", ") { ".${it.lowercase().removePrefix(".")}" }
            )
        } else null

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

        // List the pickable extensions so the user knows what to look for even before erring.
        if (supportedLabel != null) {
            Text(
                text = supportedLabel,
                style = small,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        StandardDialogButtons(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = actualConfirmText,
            // One funnel for a pick: the button posts the same Confirm the tree and the path field post,
            // so an accepted path always reaches the model - which is where the directory memory lives.
            onConfirm = { state.pickable?.let { screenModel.onEvent(FilePickerEvent.Confirm(it)) } },
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
