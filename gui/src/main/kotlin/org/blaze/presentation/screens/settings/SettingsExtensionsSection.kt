package org.blaze.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.components.ExtensionIcon
import org.blaze.presentation.components.ResolverOptionsDialog
import org.blaze.presentation.screens.settings.state.HandlerUiState
import org.blaze.presentation.screens.settings.state.SettingsState
import org.blaze.resolver.core.LinkResolverRegistry
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Link-handlers category: the installed resolver list (built-in + extension plugins), the
 * always-ask preference, and the actions that touch the extensions directory (install/reload).
 * Enable/disable and always-ask are buffered in the draft; install/remove/reload act
 * immediately because they change on-disk state the draft cannot represent.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun ExtensionsSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit,
    resolverRegistry: LinkResolverRegistry,
    onInstallHandler: () -> Unit
) {
    val hStrings = strings.settings.handlers
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    // The handler whose option editor is open, if any. Editing is buffered: confirming stages the
    // values into the draft (SettingsEvent.UpdateHandlerOptions); Apply/OK persists them.
    var optionsTarget by remember { mutableStateOf<HandlerUiState?>(null) }

    SettingsGroup("handlers.sites", strings) {
        Text(
            text = hStrings.supportedSitesDesc,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info
        )

        if (state.handlers.isEmpty()) {
            Text(hStrings.emptyHandlers)
        } else {
            state.handlers.forEach { handler ->
                HandlerRow(
                    handler = handler,
                    enabled = state.effectiveHandlerEnabled(handler),
                    infoStyle = infoStyle,
                    strings = strings,
                    registry = resolverRegistry,
                    onToggle = { checked -> onEvent(SettingsEvent.ToggleHandler(handler.id, checked)) },
                    onConfigure = { optionsTarget = handler },
                    onRemove = { onEvent(SettingsEvent.RemoveExtension(handler.id)) }
                )
            }
        }

        SettingRow("handlers.alwaysAsk") {
            CheckboxRow(
                text = hStrings.alwaysAskLabel,
                checked = state.effectiveAlwaysAsk,
                onCheckedChange = { checked -> onEvent(SettingsEvent.UpdateAlwaysAskHandler(checked)) }
            )
            Text(
                text = hStrings.alwaysAskDesc,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.padding(start = DependentIndent)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onInstallHandler) {
                Text(hStrings.installAction)
            }
            OutlinedButton(onClick = { onEvent(SettingsEvent.ReloadHandlers) }) {
                Text(hStrings.reloadAction)
            }
        }
    }

    SettingsGroup("handlers.dir", strings) {
        SettingRow("handlers.extensionsDir") {
            ReadOnlyPathField(
                label = hStrings.extensionsDirLabel,
                path = state.extensionDir,
                description = hStrings.extensionsDirDesc,
                infoStyle = infoStyle
            )
        }
    }

    optionsTarget?.let { handler ->
        val descriptors = resolverRegistry.optionDescriptors(handler.resolver)
        if (descriptors.isNotEmpty()) {
            val persisted = resolverRegistry.handlerSettings(handler.id).values
            ResolverOptionsDialog(
                title = hStrings.optionsTitle(handler.displayName),
                descriptors = descriptors,
                initialValues = state.effectiveHandlerOptions(handler.id, persisted),
                okLabel = strings.common.ok,
                cancelLabel = strings.common.cancel,
                restoreDefaultsLabel = hStrings.restoreDefaultsAction,
                onDismiss = { optionsTarget = null },
                onConfirm = { values, _ ->
                    onEvent(SettingsEvent.UpdateHandlerOptions(handler.id, values))
                    optionsTarget = null
                }
            )
        }
    }
}

/** A single handler row: icon, enable checkbox, built-in/extension tag and (for plugins) Remove. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun HandlerRow(
    handler: HandlerUiState,
    enabled: Boolean,
    infoStyle: androidx.compose.ui.text.TextStyle,
    strings: BlazeStrings,
    registry: LinkResolverRegistry,
    onToggle: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit
) {
    val hStrings = strings.settings.handlers
    // Only configurable handlers (those that declare options) get the gear; a plain plugin has
    // nothing to edit, so an empty button would be dead space.
    val configurable = registry.optionDescriptors(handler.resolver).isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExtensionIcon(
                handler = handler.resolver,
                registry = registry,
                size = 16.dp
            )
            CheckboxRow(
                text = handler.displayName,
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.weight(1f)
            )
            if (configurable) {
                OutlinedButton(onClick = onConfigure) {
                    Text(hStrings.optionsAction)
                }
            }
            Text(
                text = if (handler.isPlugin) hStrings.pluginTag else hStrings.builtinTag,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info
            )
            if (handler.isPlugin) {
                OutlinedButton(onClick = onRemove) {
                    Text(hStrings.removeAction)
                }
            }
        }
        Text(
            text = handler.description,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.padding(start = DependentIndent)
        )
    }
}
