package org.blaze.presentation.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BaseDialog
import org.blaze.presentation.components.ExtensionIcon
import org.blaze.presentation.components.StandardDialogButtons
import org.blaze.presentation.components.Island
import org.blaze.presentation.components.ResolverOptionsDialog
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.theme.BlazeTypography
import org.blaze.resolver.core.LinkResolverRegistry
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.koin.compose.koinInject

/**
 * The Extensions tool-window panel: a live view of the installed link handlers that sits on the
 * main window's stripe, next to Downloads. Every control here takes effect immediately — enable /
 * disable, install / remove, per-handler options and ordering priority — the way an IDE tool window
 * edits its own state rather than a modal draft. Global preferences (always-ask, extensions folder,
 * request timeout) intentionally stay in the Settings EXTENSIONS category.
 *
 * Content is framed as an [Island] so it floats on the window shell with the same gap the
 * downloads view uses.
 */
class ExtensionsPanel : Screen {

    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<ExtensionsScreenModel>()
        val state by screenModel.state.collectAsState()
        val strings = blazeStrings
        val eStrings = strings.extensions
        val hStrings = strings.settings.handlers
        val registry = koinInject<LinkResolverRegistry>()

        val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

        var showJarPicker by remember { mutableStateOf(false) }
        var optionsTarget by remember { mutableStateOf<ExtensionUiRow?>(null) }
        var removeTarget by remember { mutableStateOf<ExtensionUiRow?>(null) }

        Island(modifier = Modifier.fillMaxSize()) {
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
                    Text(text = eStrings.title, style = BlazeTypography.emphasizedHeader)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { showJarPicker = true }) {
                        Text(eStrings.installAction)
                    }
                    OutlinedButton(onClick = { screenModel.onEvent(ExtensionsEvent.Reload) }) {
                        Text(eStrings.reloadAction)
                    }
                }
                Text(text = eStrings.subtitle, style = infoStyle, color = JewelTheme.globalColors.text.info)

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
                            registry = registry,
                            infoStyle = infoStyle,
                            builtinTag = eStrings.builtinTag,
                            pluginTag = eStrings.pluginTag,
                            optionsAction = eStrings.optionsAction,
                            removeAction = eStrings.removeAction,
                            onToggle = { checked -> screenModel.onEvent(ExtensionsEvent.Toggle(row.id, checked)) },
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
                onDismiss = { showJarPicker = false },
                onPick = { path ->
                    showJarPicker = false
                    screenModel.onEvent(ExtensionsEvent.Install(path.toString()))
                },
                fileFilter = { it.toFile().extension.equals("jar", ignoreCase = true) }
            )
        }

        optionsTarget?.let { row ->
            val persisted = registry.handlerSettings(row.id).values
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
                    screenModel.onEvent(ExtensionsEvent.SaveOptions(row.id, values))
                    screenModel.onEvent(ExtensionsEvent.SetPriority(row.id, priority))
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
                    screenModel.onEvent(ExtensionsEvent.Uninstall(row.id))
                    removeTarget = null
                }
            )
        }
    }
}

/** One handler row: icon, live enable checkbox, built-in/extension tag, config gear and (plugins) remove. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ExtensionRow(
    row: ExtensionUiRow,
    registry: LinkResolverRegistry,
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
            ExtensionIcon(handler = row.resolver, registry = registry, size = 16.dp)
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
