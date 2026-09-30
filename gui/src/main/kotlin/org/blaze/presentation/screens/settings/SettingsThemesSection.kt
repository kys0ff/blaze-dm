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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.components.ThemeOptionsDialog
import org.blaze.presentation.screens.settings.state.SettingsState
import org.blaze.presentation.screens.settings.state.ThemeUiState
import org.blaze.theming.core.ThemeRegistry
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

/**
 * Themes category: the installed color themes (built-in + extension plugins) and the actions
 * on the themes directory. Selection is buffered in the draft so a wrong click never restyles
 * the running app before Apply. A configurable theme also gets an Options editor; its answers
 * are staged into the draft the same way (and previewed on Apply through the registry's live
 * settings flow).
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun ThemesSection(
    state: SettingsState,
    strings: BlazeStrings,
    themeRegistry: ThemeRegistry,
    onEvent: (SettingsEvent) -> Unit,
    onInstallTheme: () -> Unit
) {
    val tStrings = strings.settings.themes
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    // The theme whose option editor is open, if any. Editing is buffered: confirming stages the
    // values into the draft (SettingsEvent.UpdateThemeOptions); Apply/OK persists them.
    var optionsTarget by remember { mutableStateOf<ThemeUiState?>(null) }

    SettingsGroup("themes.color", strings) {
        SettingRow("themes.colorTheme") {
            Text(
                text = tStrings.colorThemeDesc,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.themes.forEach { theme ->
                    ThemeRow(
                        theme = theme,
                        active = state.effectiveThemeActive(theme),
                        infoStyle = infoStyle,
                        strings = strings,
                        onSelect = { onEvent(SettingsEvent.SelectTheme(theme.id)) },
                        onConfigure = { optionsTarget = theme },
                        onRemove = { onEvent(SettingsEvent.RemoveTheme(theme.id)) }
                    )
                }
            }
        }
    }

    SettingsGroup("themes.chrome", strings) {
        SettingRow("themes.ambientGlow") {
            CheckboxRow(
                text = tStrings.ambientGlowLabel,
                checked = state.appSettings.ambientGlowEnabled,
                onCheckedChange = { onEvent(SettingsEvent.UpdateAmbientGlowEnabled(it)) }
            )
            Text(
                text = tStrings.ambientGlowDesc,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.padding(start = DependentIndent)
            )
        }
    }

    SettingsGroup("themes.dir", strings) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onInstallTheme) {
                Text(tStrings.installAction)
            }
            OutlinedButton(onClick = { onEvent(SettingsEvent.ReloadThemes) }) {
                Text(tStrings.reloadAction)
            }
        }
        SettingRow("themes.dir") {
            ReadOnlyPathField(
                label = tStrings.themesDirLabel,
                path = state.themesDir,
                description = tStrings.themesDirDesc,
                infoStyle = infoStyle
            )
        }
    }

    optionsTarget?.let { theme ->
        val descriptors = themeRegistry.optionDescriptors(theme.id)
        if (descriptors.isNotEmpty()) {
            val persisted = themeRegistry.themeConfig(theme.id).values
            ThemeOptionsDialog(
                title = tStrings.optionsTitle(theme.displayName),
                descriptors = descriptors,
                initialValues = state.effectiveThemeOptions(theme.id, persisted),
                okLabel = strings.common.ok,
                cancelLabel = strings.common.cancel,
                restoreDefaultsLabel = tStrings.restoreDefaultsAction,
                colorFormatHint = tStrings.colorFormatHint,
                onDismiss = { optionsTarget = null },
                onConfirm = { values ->
                    onEvent(SettingsEvent.UpdateThemeOptions(theme.id, values))
                    optionsTarget = null
                }
            )
        }
    }
}

/** A single theme row: selection radio, built-in/extension tag, (for plugins) Remove, and a byline. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ThemeRow(
    theme: ThemeUiState,
    active: Boolean,
    infoStyle: TextStyle,
    strings: BlazeStrings,
    onSelect: () -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit
) {
    val tStrings = strings.settings.themes
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButtonRow(
                text = theme.displayName,
                selected = active,
                onClick = onSelect,
                modifier = Modifier.weight(1f)
            )
            if (theme.configurable) {
                OutlinedButton(onClick = onConfigure) {
                    Text(tStrings.optionsAction)
                }
            }
            Text(
                text = if (theme.isPlugin) tStrings.pluginTag else tStrings.builtinTag,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info
            )
            if (theme.isPlugin) {
                OutlinedButton(onClick = onRemove) {
                    Text(tStrings.removeAction)
                }
            }
        }
        Text(
            text = theme.description,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.padding(start = DependentIndent)
        )
        if (theme.author.isNotBlank() || theme.version.isNotBlank()) {
            Text(
                text = tStrings.themeByline(theme.author, theme.version),
                style = infoStyle,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.padding(start = DependentIndent)
            )
        }
    }
}
