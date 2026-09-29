package org.blaze.presentation.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.settings.state.SettingsState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Text

/**
 * Notifications category: which download lifecycle events may reach the desktop, and whether Blaze
 * may speak at all while its window is already in sight.
 *
 * Like the other app-shell options the checkboxes edit the buffered draft, so an accidental click
 * cannot start posting system notices before Apply.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun NotificationsSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit
) {
    val nStrings = strings.settings.notifications
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    if (!state.notificationsSupported) {
        SettingsSection(nStrings.header) {
            Text(
                text = nStrings.unsupportedNote,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info
            )
        }
        return
    }

    val enabled = state.appSettings.desktopNotificationsEnabled

    SettingsSection(nStrings.header) {
        CheckboxRow(
            text = nStrings.enabledLabel,
            checked = enabled,
            onCheckedChange = { onEvent(SettingsEvent.UpdateDesktopNotificationsEnabled(it)) }
        )
        Text(
            text = nStrings.enabledDesc,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.padding(start = DependentIndent)
        )
        // Dependent options: which events count only matters while notifications are on.
        if (enabled) {
            Column(modifier = Modifier.padding(start = DependentIndent)) {
                CheckboxRow(
                    text = nStrings.completedLabel,
                    checked = state.appSettings.notifyOnCompleted,
                    onCheckedChange = { onEvent(SettingsEvent.UpdateNotifyOnCompleted(it)) }
                )
                CheckboxRow(
                    text = nStrings.failedLabel,
                    checked = state.appSettings.notifyOnFailed,
                    onCheckedChange = { onEvent(SettingsEvent.UpdateNotifyOnFailed(it)) }
                )
                CheckboxRow(
                    text = nStrings.restartedLabel,
                    checked = state.appSettings.notifyOnRestarted,
                    onCheckedChange = { onEvent(SettingsEvent.UpdateNotifyOnRestarted(it)) }
                )
            }
        }
    }

    SettingsSection(nStrings.attentionHeader) {
        CheckboxRow(
            text = nStrings.whileInSightLabel,
            checked = state.appSettings.notifyWhileWindowInSight,
            enabled = enabled,
            onCheckedChange = { onEvent(SettingsEvent.UpdateNotifyWhileInSight(it)) }
        )
        Text(
            text = nStrings.whileInSightDesc,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.padding(start = DependentIndent)
        )
    }
}
