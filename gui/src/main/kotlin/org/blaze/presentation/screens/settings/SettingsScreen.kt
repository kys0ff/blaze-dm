package org.blaze.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.theme.IdeColors
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

class SettingsScreen : Screen {
    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<SettingsScreenModel>()
        val state by screenModel.state.collectAsState()
        val strings = blazeStrings

        Column(modifier = Modifier.fillMaxSize()) {
            ToolWindowHeader(title = strings.settings.title)

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Left Navigation Sidebar
                Column(
                    modifier = Modifier
                        .width(180.dp)
                        .fillMaxHeight()
                        .background(JewelTheme.globalColors.panelBackground)
                        .border(1.dp, JewelTheme.globalColors.borders.normal)
                        .padding(vertical = 8.dp)
                ) {
                    val categories = listOf(
                        SettingsCategory.GENERAL to strings.settings.generalCategory,
                        SettingsCategory.DOWNLOADS to strings.settings.downloadsCategory,
                        SettingsCategory.FILES to strings.settings.filesCategory
                    )

                    categories.forEach { (cat, label) ->
                        val isSelected = state.currentCategory == cat
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) JewelTheme.globalColors.borders.normal.copy(alpha = 0.4f) else JewelTheme.globalColors.panelBackground)
                                .clickable { screenModel.onEvent(SettingsEvent.ChangeCategory(cat)) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = JewelTheme.defaultTextStyle.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            )
                        }
                    }
                }

                // Right Panel Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    when (state.currentCategory) {
                        SettingsCategory.GENERAL -> {
                            Text(
                                text = strings.settings.startupHeader,
                                style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            )
                            CheckboxRow(
                                text = strings.settings.resumeOnStartupLabel,
                                checked = state.settings.resumeDownloadsOnStartup,
                                onCheckedChange = { screenModel.onEvent(SettingsEvent.UpdateResumeDownloadsOnStartup(it)) }
                            )
                            CheckboxRow(
                                text = strings.settings.startQueuedOnStartupLabel,
                                checked = state.settings.startQueuedOnStartup,
                                onCheckedChange = { screenModel.onEvent(SettingsEvent.UpdateStartQueuedOnStartup(it)) }
                            )
                        }

                        SettingsCategory.DOWNLOADS -> {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(
                                    text = strings.settings.concurrencyHeader,
                                    style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(text = strings.settings.maxConcurrentDownloadsLabel)
                                    TextField(
                                        value = state.maxConcurrentDownloadsText,
                                        onValueChange = { screenModel.onEvent(SettingsEvent.UpdateMaxConcurrentDownloads(it)) },
                                        outline = if (state.maxConcurrentDownloadsError != null) Outline.Error else Outline.None,
                                        modifier = Modifier.width(100.dp)
                                    )
                                    state.maxConcurrentDownloadsError?.let {
                                        Text(text = it, color = IdeColors.error, style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp))
                                    }
                                    Text(
                                        text = strings.settings.maxConcurrentDownloadsDesc,
                                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                                        color = JewelTheme.globalColors.text.info
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(text = strings.settings.maxConnectionsLabel)
                                    TextField(
                                        value = state.maxConnectionsPerDownloadText,
                                        onValueChange = { screenModel.onEvent(SettingsEvent.UpdateMaxConnectionsPerDownload(it)) },
                                        outline = if (state.maxConnectionsPerDownloadError != null) Outline.Error else Outline.None,
                                        modifier = Modifier.width(100.dp)
                                    )
                                    state.maxConnectionsPerDownloadError?.let {
                                        Text(text = it, color = IdeColors.error, style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp))
                                    }
                                    Text(
                                        text = strings.settings.maxConnectionsDesc,
                                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                                        color = JewelTheme.globalColors.text.info
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = strings.settings.bandwidthHeader,
                                    style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                )
                                CheckboxRow(
                                    text = strings.settings.speedLimitEnabledLabel,
                                    checked = state.settings.globalSpeedLimitEnabled,
                                    onCheckedChange = { screenModel.onEvent(SettingsEvent.UpdateSpeedLimitEnabled(it)) }
                                )
                                if (state.settings.globalSpeedLimitEnabled) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(text = strings.settings.speedLimitLabel)
                                        TextField(
                                            value = state.globalSpeedLimitKbpsText,
                                            onValueChange = { screenModel.onEvent(SettingsEvent.UpdateSpeedLimitKbps(it)) },
                                            outline = if (state.globalSpeedLimitKbpsError != null) Outline.Error else Outline.None,
                                            modifier = Modifier.width(150.dp)
                                        )
                                        state.globalSpeedLimitKbpsError?.let {
                                            Text(text = it, color = IdeColors.error, style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = strings.settings.retryHeader,
                                    style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                )
                                CheckboxRow(
                                    text = strings.settings.autoRetryLabel,
                                    checked = state.settings.autoRetryFailed,
                                    onCheckedChange = { screenModel.onEvent(SettingsEvent.UpdateAutoRetryFailed(it)) }
                                )
                                if (state.settings.autoRetryFailed) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(text = strings.settings.maxRetriesLabel)
                                            TextField(
                                                value = state.maxRetriesText,
                                                onValueChange = { screenModel.onEvent(SettingsEvent.UpdateMaxRetries(it)) },
                                                outline = if (state.maxRetriesError != null) Outline.Error else Outline.None,
                                                modifier = Modifier.width(100.dp)
                                            )
                                            state.maxRetriesError?.let {
                                                Text(text = it, color = IdeColors.error, style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp))
                                            }
                                        }

                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(text = strings.settings.retryDelayLabel)
                                            TextField(
                                                value = state.retryDelaySecondsText,
                                                onValueChange = { screenModel.onEvent(SettingsEvent.UpdateRetryDelaySeconds(it)) },
                                                outline = if (state.retryDelaySecondsError != null) Outline.Error else Outline.None,
                                                modifier = Modifier.width(100.dp)
                                            )
                                            state.retryDelaySecondsError?.let {
                                                Text(text = it, color = IdeColors.error, style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        SettingsCategory.FILES -> {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(
                                    text = strings.settings.destinationHeader,
                                    style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(text = strings.settings.defaultDownloadDirLabel)
                                    var showDirPicker by remember { mutableStateOf(false) }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = state.settings.defaultDownloadDir,
                                            modifier = Modifier.weight(1f),
                                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
                                        )
                                        OutlinedButton(onClick = { showDirPicker = true }) {
                                            Text(strings.common.browse)
                                        }
                                    }

                                    if (showDirPicker) {
                                        FilePickerDialog(
                                            onDismiss = { showDirPicker = false },
                                            onPick = { path ->
                                                screenModel.onEvent(SettingsEvent.UpdateDefaultDownloadDir(path.toString()))
                                                showDirPicker = false
                                            },
                                            mode = FilePickerMode.Directory
                                        )
                                    }
                                }

                                CheckboxRow(
                                    text = strings.settings.askWhereToSaveLabel,
                                    checked = state.settings.askWhereToSave,
                                    onCheckedChange = { screenModel.onEvent(SettingsEvent.UpdateAskWhereToSave(it)) }
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = strings.settings.fileConflictsHeader,
                                    style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                )
                                Text(text = strings.settings.fileConflictBehaviorLabel)

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RadioButtonRow(
                                        text = strings.settings.askOption,
                                        selected = state.settings.fileConflictBehavior == FileConflictBehavior.ASK,
                                        onClick = { screenModel.onEvent(SettingsEvent.UpdateFileConflictBehavior(FileConflictBehavior.ASK)) }
                                    )
                                    RadioButtonRow(
                                        text = strings.settings.overwriteOption,
                                        selected = state.settings.fileConflictBehavior == FileConflictBehavior.OVERWRITE,
                                        onClick = { screenModel.onEvent(SettingsEvent.UpdateFileConflictBehavior(FileConflictBehavior.OVERWRITE)) }
                                    )
                                    RadioButtonRow(
                                        text = strings.settings.skipOption,
                                        selected = state.settings.fileConflictBehavior == FileConflictBehavior.SKIP,
                                        onClick = { screenModel.onEvent(SettingsEvent.UpdateFileConflictBehavior(FileConflictBehavior.SKIP)) }
                                    )
                                    RadioButtonRow(
                                        text = strings.settings.renameOption,
                                        selected = state.settings.fileConflictBehavior == FileConflictBehavior.RENAME,
                                        onClick = { screenModel.onEvent(SettingsEvent.UpdateFileConflictBehavior(FileConflictBehavior.RENAME)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Actions Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JewelTheme.globalColors.panelBackground)
                    .border(1.dp, JewelTheme.globalColors.borders.normal)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DefaultButton(onClick = { screenModel.onEvent(SettingsEvent.SaveSettings) }) {
                    Text(strings.common.ok)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = { screenModel.onEvent(SettingsEvent.ResetSettings) }) {
                    Text(strings.common.cancel)
                }
            }
        }
    }
}
