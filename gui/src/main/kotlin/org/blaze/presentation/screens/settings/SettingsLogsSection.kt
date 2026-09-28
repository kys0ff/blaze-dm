package org.blaze.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.engine.settings.LogLevel
import org.blaze.i18n.BlazeStrings
import org.blaze.logging.LogConfigurator
import org.blaze.presentation.screens.settings.state.SettingsState
import org.blaze.presentation.util.openInFileManager
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Logging category: verbosity, file logging and the log-folder shortcuts. The level option list
 * mirrors [LogLevel] one-to-one; new engine levels must be added here too.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun LogsSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit,
    logConfigurator: LogConfigurator
) {
    val lStrings = strings.settings.logging
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    SettingsSection(lStrings.header) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = lStrings.levelLabel)
            val levelOptions = listOf(
                LogLevel.OFF to lStrings.offOption,
                LogLevel.ERROR to lStrings.errorOption,
                LogLevel.WARN to lStrings.warnOption,
                LogLevel.INFO to lStrings.infoOption,
                LogLevel.DEBUG to lStrings.debugOption,
                LogLevel.TRACE to lStrings.traceOption
            )
            levelOptions.forEach { (level, label) ->
                RadioButtonRow(
                    text = label,
                    selected = state.settings.logLevel == level,
                    onClick = { onEvent(SettingsEvent.UpdateLogLevel(level)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = lStrings.levelDesc,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info
            )
        }

        CheckboxRow(
            text = lStrings.fileLoggingLabel,
            checked = state.settings.fileLoggingEnabled,
            onCheckedChange = { onEvent(SettingsEvent.UpdateFileLoggingEnabled(it)) }
        )
        Text(
            text = lStrings.fileLoggingDesc,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.padding(start = DependentIndent)
        )
    }

    SettingsSection(lStrings.filesHeader) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = lStrings.logDirLabel)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Same read-only path field pattern as the download dir.
                TextField(
                    value = TextFieldValue(logConfigurator.logDir.toString()),
                    onValueChange = {},
                    enabled = false,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { openInFileManager(logConfigurator.logDir) }
                ) {
                    Text(lStrings.openFolderAction)
                }
            }
            Text(
                text = lStrings.logDirDesc,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info
            )
        }
    }
}
