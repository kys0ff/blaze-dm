package org.blaze.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.engine.api.DownloadPriority
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.settings.state.SettingsState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

/**
 * Downloads category: everything that steers the transfer engine itself - concurrency,
 * bandwidth, retries, network/protocol and torrent behavior. Dependent fields stay visible but
 * disabled (IDE convention) so the layout never jumps when a master toggle flips.
 *
 * Each preference is wrapped in a [SettingRow] keyed to its [SettingsRegistry] id so the screen and the
 * search index stay one source; a query hides the rows it does not match and the empty group headers.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun DownloadsSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit
) {
    val speedLimitEnabled = state.settings.globalSpeedLimitEnabled
    val autoRetryEnabled = state.settings.autoRetryFailed
    val seedingEnabled = state.settings.enableSeeding

    SettingsGroup("concurrency", strings) {
        SettingRow("downloads.maxConcurrentDownloads") {
            NumberField(
                label = strings.settings.maxConcurrentDownloadsLabel,
                value = state.maxConcurrentDownloadsText,
                error = state.maxConcurrentDownloadsError,
                description = strings.settings.maxConcurrentDownloadsDesc,
                onValueChange = { onEvent(SettingsEvent.UpdateMaxConcurrentDownloads(it)) }
            )
        }
        SettingRow("downloads.maxConnections") {
            NumberField(
                label = strings.settings.maxConnectionsLabel,
                value = state.maxConnectionsPerDownloadText,
                error = state.maxConnectionsPerDownloadError,
                description = strings.settings.maxConnectionsDesc,
                onValueChange = { onEvent(SettingsEvent.UpdateMaxConnectionsPerDownload(it)) }
            )
        }

        // Default priority seeded onto every new download; the queue order and connection split
        // both key off it, so it belongs beside the concurrency knobs it interacts with.
        SettingRow("downloads.defaultPriority") {
            Text(text = strings.settings.defaultPriorityLabel)
            Text(
                text = strings.settings.defaultPriorityDesc,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = JewelTheme.globalColors.text.info,
            )
            val priorityOptions = listOf(
                DownloadPriority.IDLE to strings.downloads.priority.idle,
                DownloadPriority.LOW to strings.downloads.priority.low,
                DownloadPriority.NORMAL to strings.downloads.priority.normal,
                DownloadPriority.HIGH to strings.downloads.priority.high,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                priorityOptions.forEach { (level, label) ->
                    RadioButtonRow(
                        text = label,
                        selected = state.settings.defaultDownloadPriority == level,
                        onClick = { onEvent(SettingsEvent.UpdateDefaultDownloadPriority(level)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    SettingsGroup("bandwidth", strings) {
        SettingRow("downloads.speedLimitEnabled") {
            CheckboxRow(
                text = strings.settings.speedLimitEnabledLabel,
                checked = speedLimitEnabled,
                onCheckedChange = { onEvent(SettingsEvent.UpdateSpeedLimitEnabled(it)) }
            )
        }
        // Dependent option: indented and disabled (not hidden) when off, like IDE settings,
        // so the layout doesn't jump.
        SettingRow("downloads.speedLimit", modifier = Modifier.padding(start = DependentIndent)) {
            NumberField(
                label = strings.settings.speedLimitLabel,
                value = state.globalSpeedLimitKbpsText,
                error = state.globalSpeedLimitKbpsError,
                enabled = speedLimitEnabled,
                fieldWidth = 150.dp,
                onValueChange = { onEvent(SettingsEvent.UpdateSpeedLimitKbps(it)) }
            )
        }
        SettingRow("downloads.snailModeEnabled") {
            CheckboxRow(
                text = strings.settings.snailModeEnabledLabel,
                checked = state.settings.snailModeEnabled,
                onCheckedChange = { onEvent(SettingsEvent.UpdateSnailModeEnabled(it)) }
            )
        }
        SettingRow("downloads.snailSpeed", modifier = Modifier.padding(start = DependentIndent)) {
            NumberField(
                label = strings.settings.snailSpeedLabel,
                value = state.snailSpeedKbpsText,
                error = state.snailSpeedKbpsError,
                description = strings.settings.snailSpeedDesc,
                enabled = state.settings.snailModeEnabled,
                fieldWidth = 150.dp,
                onValueChange = { onEvent(SettingsEvent.UpdateSnailSpeedKbps(it)) }
            )
        }
    }

    SettingsGroup("retry", strings) {
        SettingRow("downloads.autoRetry") {
            CheckboxRow(
                text = strings.settings.autoRetryLabel,
                checked = autoRetryEnabled,
                onCheckedChange = { onEvent(SettingsEvent.UpdateAutoRetryFailed(it)) }
            )
        }
        Row(
            modifier = Modifier.padding(start = DependentIndent),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingRow("downloads.maxRetries") {
                NumberField(
                    label = strings.settings.maxRetriesLabel,
                    value = state.maxRetriesText,
                    error = state.maxRetriesError,
                    enabled = autoRetryEnabled,
                    onValueChange = { onEvent(SettingsEvent.UpdateMaxRetries(it)) }
                )
            }
            SettingRow("downloads.retryDelay") {
                NumberField(
                    label = strings.settings.retryDelayLabel,
                    value = state.retryDelaySecondsText,
                    error = state.retryDelaySecondsError,
                    enabled = autoRetryEnabled,
                    onValueChange = { onEvent(SettingsEvent.UpdateRetryDelaySeconds(it)) }
                )
            }
        }
        SettingRow("downloads.exponentialBackoff", modifier = Modifier.padding(start = DependentIndent)) {
            CheckboxRow(
                text = strings.settings.exponentialBackoffLabel,
                checked = state.settings.exponentialBackoff,
                enabled = autoRetryEnabled,
                onCheckedChange = { onEvent(SettingsEvent.UpdateExponentialBackoff(it)) }
            )
        }
    }

    SettingsGroup("network", strings) {
        SettingRow("downloads.userAgent") {
            LabeledTextField(
                label = strings.settings.userAgentLabel,
                value = state.userAgentText,
                description = strings.settings.userAgentDesc,
                onValueChange = { onEvent(SettingsEvent.UpdateUserAgent(it)) }
            )
        }
        SettingRow("downloads.maxRedirects") {
            NumberField(
                label = strings.settings.maxRedirectsLabel,
                value = state.maxRedirectsText,
                error = state.maxRedirectsError,
                description = strings.settings.maxRedirectsDesc,
                onValueChange = { onEvent(SettingsEvent.UpdateMaxRedirects(it)) }
            )
        }
    }

    SettingsGroup("torrent", strings) {
        SettingRow("downloads.maxPeerConnections") {
            NumberField(
                label = strings.settings.maxPeerConnectionsLabel,
                value = state.maxPeerConnectionsText,
                error = state.maxPeerConnectionsError,
                description = strings.settings.maxPeerConnectionsDesc,
                onValueChange = { onEvent(SettingsEvent.UpdateMaxPeerConnections(it)) }
            )
        }
        SettingRow("downloads.seedingEnabled") {
            CheckboxRow(
                text = strings.settings.seedingEnabledLabel,
                checked = seedingEnabled,
                onCheckedChange = { onEvent(SettingsEvent.UpdateEnableSeeding(it)) }
            )
        }
        SettingRow("downloads.unlimitedSeeding", modifier = Modifier.padding(start = DependentIndent)) {
            CheckboxRow(
                text = strings.settings.unlimitedSeedingLabel,
                checked = state.settings.unlimitedSeeding,
                enabled = seedingEnabled,
                onCheckedChange = { onEvent(SettingsEvent.UpdateUnlimitedSeeding(it)) }
            )
        }
        SettingRow("downloads.seedTimeLimit", modifier = Modifier.padding(start = DependentIndent)) {
            NumberField(
                label = strings.settings.seedTimeLimitLabel,
                value = state.seedTimeLimitMinutesText,
                error = state.seedTimeLimitMinutesError,
                enabled = seedingEnabled && !state.settings.unlimitedSeeding,
                fieldWidth = 150.dp,
                onValueChange = { onEvent(SettingsEvent.UpdateSeedTimeLimitMinutes(it)) }
            )
        }
        SettingRow("downloads.seedRatioLimit", modifier = Modifier.padding(start = DependentIndent)) {
            NumberField(
                label = strings.settings.seedRatioLimitLabel,
                value = state.seedRatioLimitText,
                error = state.seedRatioLimitError,
                description = strings.settings.seedRatioLimitDesc,
                enabled = seedingEnabled,
                fieldWidth = 150.dp,
                onValueChange = { onEvent(SettingsEvent.UpdateSeedRatioLimit(it)) }
            )
        }
        SettingRow("downloads.torrentFilePreviewEnabled") {
            CheckboxRow(
                text = strings.settings.torrentFilePreviewLabel,
                checked = state.appSettings.torrentFilePreviewEnabled,
                onCheckedChange = { onEvent(SettingsEvent.UpdateTorrentFilePreview(it)) }
            )
            Text(
                text = strings.settings.torrentFilePreviewDesc,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.padding(start = DependentIndent),
            )
        }
    }
}
