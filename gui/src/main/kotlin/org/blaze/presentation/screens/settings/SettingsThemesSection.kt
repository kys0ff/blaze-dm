package org.blaze.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.settings.state.SettingsState
import org.blaze.presentation.screens.settings.state.ThemeUiState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

/**
 * Themes category: the installed color themes (built-in + extension plugins) and the actions
 * on the themes directory. Selection is buffered in the draft so a wrong click never restyles
 * the running app before Apply.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun ThemesSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit,
    onInstallTheme: () -> Unit
) {
    val tStrings = strings.settings.themes
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    SettingsSection(tStrings.colorThemeHeader) {
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
                    onRemove = { onEvent(SettingsEvent.RemoveTheme(theme.id)) }
                )
            }
        }
    }

    SettingsSection(tStrings.chromeHeader) {
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

    SettingsSection(tStrings.themesDirHeader) {
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
        ReadOnlyPathField(
            label = tStrings.themesDirLabel,
            path = state.themesDir,
            description = tStrings.themesDirDesc,
            infoStyle = infoStyle
        )
    }
}

/** A single theme row: selection radio, built-in/extension tag and (for plugins) Remove. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ThemeRow(
    theme: ThemeUiState,
    active: Boolean,
    infoStyle: TextStyle,
    strings: BlazeStrings,
    onSelect: () -> Unit,
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
    }
}
