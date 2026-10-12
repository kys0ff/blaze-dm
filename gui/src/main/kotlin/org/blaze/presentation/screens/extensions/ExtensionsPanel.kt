package org.blaze.presentation.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BaseDialog
import org.blaze.presentation.components.ExtensionIcon
import org.blaze.presentation.components.StandardDialogButtons
import org.blaze.presentation.components.ResolverOptionsDialog
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.components.ToolWindowOptionsButton
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.components.panel.ToolWindowLayoutAction
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerContext
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The Extensions tool window: a live view of the installed link handlers that docks beside the
 * downloads list. Every control here takes effect immediately — enable / disable, install / remove,
 * per-handler options and ordering priority — the way an IDE tool window edits its own state rather
 * than a modal draft. Global preferences (always-ask, extensions folder, request timeout)
 * intentionally stay in the Settings EXTENSIONS category.
 *
 * Docking (view mode, move-to, split size, hide) is hoisted to
 * [org.blaze.presentation.screens.main.MainScreen], which mounts this inside a [org.blaze.presentation.components.panel.ToolWindowSpec];
 * the pane draws its own [ToolWindowHeader] chrome and never decides where it sits — the host frames
 * it in an island.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun ExtensionsPanel(
    model: ExtensionsWindowModel,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val strings = blazeStrings
    val eStrings = strings.extensions
    val hStrings = strings.settings.handlers
    val pane = strings.downloads.detailsPane

    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    var showJarPicker by remember { mutableStateOf(false) }
    var optionsTarget by remember { mutableStateOf<ExtensionUiRow?>(null) }
    var removeTarget by remember { mutableStateOf<ExtensionUiRow?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        ToolWindowHeader(
            title = eStrings.title,
            actions = {
                ToolWindowOptionsButton(
                    options = pane.options,
                    viewMode = state.viewMode,
                    moveTo = state.moveTo,
                    onViewMode = { model.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(it)) },
                    onMoveTo = { model.onLayoutEvent(ToolWindowLayoutAction.SetMoveTo(it)) },
                    onResize = model::resize,
                )
                // A tool window is *hidden*, not closed: the IDE draws the horizontal-line glyph for
                // that and keeps the X for closing an editor.
                ToolbarIconButton(
                    key = AllIconsKeys.General.HideToolWindow,
                    tooltip = pane.hide,
                    onClick = onHide,
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = eStrings.subtitle,
                    style = infoStyle,
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { showJarPicker = true }) {
                    Text(eStrings.installAction)
                }
                OutlinedButton(onClick = { model.onEvent(ExtensionsEvent.Reload) }) {
                    Text(eStrings.reloadAction)
                }
            }

            if (state.rows.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = eStrings.emptyTitle)
                    Text(
                        text = eStrings.emptyDescription,
                        style = infoStyle,
                        color = JewelTheme.globalColors.text.info
                    )
                }
            } else {
                state.rows.forEach { row ->
                    ExtensionRow(
                        row = row,
                        infoStyle = infoStyle,
                        builtinTag = eStrings.builtinTag,
                        pluginTag = eStrings.pluginTag,
                        optionsAction = eStrings.optionsAction,
                        removeAction = eStrings.removeAction,
                        onToggle = { checked -> model.onEvent(ExtensionsEvent.Toggle(row.id, checked)) },
                        onConfigure = { optionsTarget = row },
                        onRemove = { removeTarget = row },
                    )
                }
            }
        }
    }

    if (showJarPicker) {
        FilePickerDialog(
            title = eStrings.installAction,
            mode = FilePickerMode.File,
            // Remembers its own folder - and shares it with Settings' handler-install button, because
            // both ask for the same jar. A download picker's history has nothing to do with this.
            context = FilePickerContext.ExtensionJar,
            onDismiss = { showJarPicker = false },
            onPick = { path ->
                showJarPicker = false
                model.onEvent(ExtensionsEvent.Install(path.toString()))
            },
            fileFilter = { it.toFile().extension.equals("jar", ignoreCase = true) }
        )
    }

    optionsTarget?.let { row ->
        val persisted = model.handlerOptionValues(row.id)
        ResolverOptionsDialog(
            title = hStrings.optionsTitle(row.displayName),
            descriptors = row.options,
            initialValues = persisted,
            okLabel = strings.common.ok,
            cancelLabel = strings.common.cancel,
            restoreDefaultsLabel = hStrings.restoreDefaultsAction,
            priorityLabel = hStrings.priorityLabel,
            priorityDescription = hStrings.priorityDesc,
            initialPriority = row.priority,
            onDismiss = { optionsTarget = null },
            onConfirm = { values, priority ->
                model.onEvent(ExtensionsEvent.SaveOptions(row.id, values))
                model.onEvent(ExtensionsEvent.SetPriority(row.id, priority))
                optionsTarget = null
            }
        )
    }

    removeTarget?.let { row ->
        RemoveExtensionDialog(
            title = eStrings.removeConfirmTitle,
            body = eStrings.removeConfirm(row.displayName),
            okLabel = strings.common.ok,
            cancelLabel = strings.common.cancel,
            onDismiss = { removeTarget = null },
            onConfirm = {
                model.onEvent(ExtensionsEvent.Uninstall(row.id))
                removeTarget = null
            }
        )
    }
}

/** One handler row: icon, live enable checkbox, built-in/extension tag, config gear and (plugins) remove. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ExtensionRow(
    row: ExtensionUiRow,
    infoStyle: androidx.compose.ui.text.TextStyle,
    builtinTag: String,
    pluginTag: String,
    optionsAction: String,
    removeAction: String,
    onToggle: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExtensionIcon(iconBytes = row.iconBytes, size = 16.dp)
            CheckboxRow(
                text = row.displayName,
                checked = row.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.weight(1f)
            )
            if (row.options.isNotEmpty()) {
                OutlinedButton(onClick = onConfigure) { Text(optionsAction) }
            }
            if (row.isPlugin) {
                OutlinedButton(onClick = onRemove) { Text(removeAction) }
            }
            Text(
                text = if (row.isPlugin) pluginTag else builtinTag,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info
            )
        }
        Text(
            text = row.description,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp)
        )
    }
}

/** The panel's own delete confirmation, mirroring the IDE's short "remove this plugin?" prompt. */
@Composable
private fun RemoveExtensionDialog(
    title: String,
    body: String,
    okLabel: String,
    cancelLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    BaseDialog(onDismiss = onDismiss, title = title) {
        Text(text = body)
        StandardDialogButtons(
            dismissText = cancelLabel,
            onDismiss = onDismiss,
            confirmText = okLabel,
            onConfirm = onConfirm,
        )
    }
}
