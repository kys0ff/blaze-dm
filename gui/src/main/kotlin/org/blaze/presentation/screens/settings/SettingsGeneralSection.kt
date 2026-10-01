package org.blaze.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.engine.settings.ThemeMode
import org.blaze.i18n.BlazeLocale
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.settings.state.SettingsState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

/**
 * General category: appearance, system integration (tray, autostart, clipboard monitoring) and
 * startup/queue behavior. Each option only appears when the environment can actually honour it —
 * the same rule the rest of the shell follows (no tray on a desktop without one, no autostart on
 * an OS we cannot register with).
 *
 * Every registered preference is wrapped in a [SettingRow] keyed to its [SettingsRegistry] id, so the
 * search index and this screen are the same source: a row shown here is searchable, and a row the search
 * offers is guaranteed to exist here.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun GeneralSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit
) {
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    SettingsGroup("appearance", strings) {
        // Language names are endonyms (English, العربية), so this row reads correctly in every
        // locale and needs no translation table of its own.
        SettingRow("general.language") {
            Text(text = strings.settings.languageLabel)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BlazeLocale.entries.forEach { locale ->
                    RadioButtonRow(
                        text = locale.displayName,
                        selected = state.appSettings.languageCode == locale.code,
                        onClick = { onEvent(SettingsEvent.UpdateLanguage(locale.code)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        SettingRow("general.theme") {
            Text(text = strings.settings.themeLabel)
            val themeOptions = listOf(
                ThemeMode.SYSTEM to strings.settings.themeSystemOption,
                ThemeMode.LIGHT to strings.settings.themeLightOption,
                ThemeMode.DARK to strings.settings.themeDarkOption
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                themeOptions.forEach { (mode, label) ->
                    RadioButtonRow(
                        text = label,
                        selected = state.settings.themeMode == mode,
                        onClick = { onEvent(SettingsEvent.UpdateThemeMode(mode)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    SettingsGroup("systemIntegration", strings) {
        val trayEnabled = state.appSettings.trayEnabled
        if (state.traySupported) {
            SettingRow("general.trayEnabled") {
                CheckboxRow(
                    text = strings.settings.trayEnabledLabel,
                    checked = trayEnabled,
                    onCheckedChange = { onEvent(SettingsEvent.UpdateTrayEnabled(it)) }
                )
                Text(
                    text = strings.settings.trayEnabledDesc,
                    style = infoStyle,
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.padding(start = DependentIndent)
                )
            }
            // Dependent option: shown only while the tray itself is on.
            if (trayEnabled) {
                SettingRow("general.minimizeToTray", modifier = Modifier.padding(start = DependentIndent)) {
                    CheckboxRow(
                        text = strings.settings.minimizeToTrayLabel,
                        checked = state.appSettings.minimizeToTrayOnClose,
                        onCheckedChange = { onEvent(SettingsEvent.UpdateMinimizeToTrayOnClose(it)) }
                    )
                }
            }
        }
        if (state.autoStartSupported) {
            SettingRow("general.runAtStartup") {
                CheckboxRow(
                    text = strings.settings.runAtStartupLabel,
                    checked = state.appSettings.runAtStartup,
                    onCheckedChange = { onEvent(SettingsEvent.UpdateRunAtStartup(it)) }
                )
                Text(
                    text = strings.settings.runAtStartupDesc,
                    style = infoStyle,
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.padding(start = DependentIndent)
                )
            }
        }
        // Clipboard watching is pure client behavior, so it carries no platform gate: when the
        // clipboard itself is unavailable the monitor quietly stays off (see AppClipboardMonitor).
        SettingRow("general.clipboardMonitoring") {
            CheckboxRow(
                text = strings.settings.clipboardMonitoringLabel,
                checked = state.appSettings.clipboardMonitoringEnabled,
                onCheckedChange = { onEvent(SettingsEvent.UpdateClipboardMonitoring(it)) }
            )
            Text(
                text = strings.settings.clipboardMonitoringDesc,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.padding(start = DependentIndent)
            )
        }
    }

    SettingsGroup("security", strings) {
        SettingRow("general.security.approveExternalClients") {
            CheckboxRow(
                text = strings.settings.approveExternalClientsLabel,
                checked = state.appSettings.approveExternalClients,
                onCheckedChange = { onEvent(SettingsEvent.UpdateApproveExternalClients(it)) }
            )
            Text(
                text = strings.settings.approveExternalClientsDesc,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.padding(start = DependentIndent)
            )
        }
        // The manager rides the switch it belongs to: with prompts off there is nothing remembered
        // to revoke (every caller passes on the token), so the list would only confuse.
        if (state.appSettings.approveExternalClients) {
            SettingRow("general.security.rememberedClients", modifier = Modifier.padding(start = DependentIndent)) {
                Text(text = strings.settings.rememberedClientsLabel)
                if (state.appSettings.rememberedClients.isEmpty()) {
                    Text(
                        text = strings.settings.rememberedClientsEmpty,
                        style = infoStyle,
                        color = JewelTheme.globalColors.text.info,
                    )
                }
                state.appSettings.rememberedClients.forEach { client ->
                    NamedItemRow(
                        name = client.displayName,
                        detail = client.id,
                        actionLabel = strings.settings.revokeAccessAction,
                        onAction = { onEvent(SettingsEvent.RevokeRememberedClient(client.id)) },
                    )
                }
            }
        }

        SettingRow("general.security.approveExtensions") {
            CheckboxRow(
                text = strings.settings.approveExtensionsLabel,
                checked = state.appSettings.approveExtensions,
                onCheckedChange = { onEvent(SettingsEvent.UpdateApproveExtensions(it)) }
            )
            Text(
                text = strings.settings.approveExtensionsDesc,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.padding(start = DependentIndent)
            )
        }
        if (state.appSettings.approveExtensions) {
            SettingRow("general.security.trustedExtensions", modifier = Modifier.padding(start = DependentIndent)) {
                Text(text = strings.settings.trustedExtensionsLabel)
                if (state.appSettings.trustedExtensions.isEmpty()) {
                    Text(
                        text = strings.settings.trustedExtensionsEmpty,
                        style = infoStyle,
                        color = JewelTheme.globalColors.text.info,
                    )
                }
                state.appSettings.trustedExtensions.forEach { extension ->
                    // Trust is per kind: a "link handler" approval never vouches for a theme jar with
                    // the same bytes, so the row says which of the two it is revoking.
                    NamedItemRow(
                        name = extension.fileName,
                        detail = if (extension.kind == "THEME") {
                            strings.settings.trustedKindTheme
                        } else {
                            strings.settings.trustedKindHandler
                        },
                        actionLabel = strings.settings.forgetAction,
                        onAction = { onEvent(SettingsEvent.ForgetTrustedExtension(extension.sha256, extension.kind)) },
                    )
                }
            }
        }
    }

    SettingsGroup("startup", strings) {
        SettingRow("general.resumeOnStartup") {
            CheckboxRow(
                text = strings.settings.resumeOnStartupLabel,
                checked = state.settings.resumeDownloadsOnStartup,
                onCheckedChange = { onEvent(SettingsEvent.UpdateResumeDownloadsOnStartup(it)) }
            )
        }
        SettingRow("general.startQueuedOnStartup") {
            CheckboxRow(
                text = strings.settings.startQueuedOnStartupLabel,
                checked = state.settings.startQueuedOnStartup,
                onCheckedChange = { onEvent(SettingsEvent.UpdateStartQueuedOnStartup(it)) }
            )
        }
    }
}

/**
 * One line in a consent manager: the approved item's human name, its stable identifier beside it
 * (a client id, an extension's kind), and a trailing link that takes the "allow always" back.
 * Revoking touches only the persisted approval staged in the draft — Apply/OK writes it away.
 */
@Composable
private fun NamedItemRow(
    name: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = name, style = JewelTheme.defaultTextStyle)
        Text(
            text = detail,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.weight(1f),
        )
        Link(text = actionLabel, onClick = onAction)
    }
}
