package org.blaze.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.engine.settings.LogLevel
import org.blaze.engine.settings.ThemeMode
import org.blaze.i18n.blazeStrings
import org.blaze.logging.LogConfigurator
import org.blaze.presentation.components.ExtensionIcon
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.screens.settings.state.SettingsCategory
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.util.openInFileManager
import org.blaze.resolver.core.LinkResolverRegistry
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import org.koin.compose.koinInject
import java.nio.file.Path

/** Settings content is indented to line up with the label of a checkbox/radio (icon + gap). */
private val DependentIndent = 27.dp

class SettingsScreen : Screen {
    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = koinScreenModel<SettingsScreenModel>()
        val state by screenModel.state.collectAsState()
        val strings = blazeStrings
        val resolverRegistry = koinInject<LinkResolverRegistry>()
        val logConfigurator = koinInject<LogConfigurator>()

        var showDirPicker by remember { mutableStateOf(false) }
        var showJarPicker by remember { mutableStateOf(false) }
        var showThemeJarPicker by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()

        val speedLimitEnabled = state.settings.globalSpeedLimitEnabled
        val autoRetryEnabled = state.settings.autoRetryFailed
        val seedingEnabled = state.settings.enableSeeding

        // Errors on fields that are disabled don't count: the user can't see or fix them.
        val downloadsHasError = state.maxConcurrentDownloadsError != null ||
                state.maxConnectionsPerDownloadError != null ||
                (speedLimitEnabled && state.globalSpeedLimitKbpsError != null) ||
                (autoRetryEnabled && (state.maxRetriesError != null || state.retryDelaySecondsError != null)) ||
                state.maxRedirectsError != null ||
                state.maxPeerConnectionsError != null ||
                (seedingEnabled && state.seedTimeLimitMinutesError != null)

        // All categories share one scroll state, so start each one at the top.
        LaunchedEffect(state.currentCategory) { scrollState.scrollTo(0) }

        Column(modifier = Modifier.fillMaxSize()) {
            ToolWindowHeader(title = strings.settings.title)

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Category list
                Column(
                    modifier = Modifier
                        .width(180.dp)
                        .fillMaxHeight()
                        .background(JewelTheme.globalColors.panelBackground)
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    val categories = listOf(
                        SettingsCategory.GENERAL to strings.settings.generalCategory,
                        SettingsCategory.DOWNLOADS to strings.settings.downloadsCategory,
                        SettingsCategory.FILES to strings.settings.filesCategory,
                        SettingsCategory.EXTENSIONS to strings.settings.handlers.category,
                        SettingsCategory.THEMES to strings.settings.themes.category,
                        SettingsCategory.LOGS to strings.settings.logsCategory
                    )

                    categories.forEach { (category, label) ->
                        SettingsNavItem(
                            label = label,
                            isSelected = state.currentCategory == category,
                            hasError = category == SettingsCategory.DOWNLOADS && downloadsHasError,
                            onClick = { screenModel.onEvent(SettingsEvent.ChangeCategory(category)) }
                        )
                    }
                }

                Divider(Orientation.Vertical, Modifier.fillMaxHeight())

                // Content. verticalScroll must come before padding, otherwise content is
                // clipped 24dp inside the panel edge instead of at the edge.
                VerticallyScrollableContainer(
                    scrollState,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.widthIn(max = 640.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            when (state.currentCategory) {
                                SettingsCategory.GENERAL -> {
                                    SettingsSection(strings.settings.appearanceHeader) {
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
                                                    onClick = {
                                                        screenModel.onEvent(
                                                            SettingsEvent.UpdateThemeMode(mode)
                                                        )
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }

                                    SettingsSection(strings.settings.startupHeader) {
                                        CheckboxRow(
                                            text = strings.settings.resumeOnStartupLabel,
                                            checked = state.settings.resumeDownloadsOnStartup,
                                            onCheckedChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateResumeDownloadsOnStartup(
                                                        it
                                                    )
                                                )
                                            }
                                        )
                                        CheckboxRow(
                                            text = strings.settings.startQueuedOnStartupLabel,
                                            checked = state.settings.startQueuedOnStartup,
                                            onCheckedChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateStartQueuedOnStartup(
                                                        it
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }

                                SettingsCategory.DOWNLOADS -> {
                                    SettingsSection(strings.settings.concurrencyHeader) {
                                        NumberField(
                                            label = strings.settings.maxConcurrentDownloadsLabel,
                                            value = state.maxConcurrentDownloadsText,
                                            error = state.maxConcurrentDownloadsError,
                                            description = strings.settings.maxConcurrentDownloadsDesc,
                                            onValueChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateMaxConcurrentDownloads(
                                                        it
                                                    )
                                                )
                                            }
                                        )
                                        NumberField(
                                            label = strings.settings.maxConnectionsLabel,
                                            value = state.maxConnectionsPerDownloadText,
                                            error = state.maxConnectionsPerDownloadError,
                                            description = strings.settings.maxConnectionsDesc,
                                            onValueChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateMaxConnectionsPerDownload(
                                                        it
                                                    )
                                                )
                                            }
                                        )
                                    }

                                    SettingsSection(strings.settings.bandwidthHeader) {
                                        CheckboxRow(
                                            text = strings.settings.speedLimitEnabledLabel,
                                            checked = speedLimitEnabled,
                                            onCheckedChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateSpeedLimitEnabled(
                                                        it
                                                    )
                                                )
                                            }
                                        )
                                        // Dependent option: indented and disabled (not hidden) when off,
                                        // like IDE settings, so the layout doesn't jump.
                                        NumberField(
                                            label = strings.settings.speedLimitLabel,
                                            value = state.globalSpeedLimitKbpsText,
                                            error = state.globalSpeedLimitKbpsError,
                                            enabled = speedLimitEnabled,
                                            fieldWidth = 150.dp,
                                            modifier = Modifier.padding(start = DependentIndent),
                                            onValueChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateSpeedLimitKbps(
                                                        it
                                                    )
                                                )
                                            }
                                        )
                                    }

                                    SettingsSection(strings.settings.retryHeader) {
                                        CheckboxRow(
                                            text = strings.settings.autoRetryLabel,
                                            checked = autoRetryEnabled,
                                            onCheckedChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateAutoRetryFailed(
                                                        it
                                                    )
                                                )
                                            }
                                        )
                                        Row(
                                            modifier = Modifier.padding(start = DependentIndent),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            NumberField(
                                                label = strings.settings.maxRetriesLabel,
                                                value = state.maxRetriesText,
                                                error = state.maxRetriesError,
                                                enabled = autoRetryEnabled,
                                                onValueChange = {
                                                    screenModel.onEvent(
                                                        SettingsEvent.UpdateMaxRetries(
                                                            it
                                                        )
                                                    )
                                                }
                                            )
                                            NumberField(
                                                label = strings.settings.retryDelayLabel,
                                                value = state.retryDelaySecondsText,
                                                error = state.retryDelaySecondsError,
                                                enabled = autoRetryEnabled,
                                                onValueChange = {
                                                    screenModel.onEvent(
                                                        SettingsEvent.UpdateRetryDelaySeconds(
                                                            it
                                                        )
                                                    )
                                                }
                                            )
                                        }
                                        CheckboxRow(
                                            text = strings.settings.exponentialBackoffLabel,
                                            checked = state.settings.exponentialBackoff,
                                            enabled = autoRetryEnabled,
                                            onCheckedChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateExponentialBackoff(
                                                        it
                                                    )
                                                )
                                            },
                                            modifier = Modifier.padding(start = DependentIndent)
                                        )
                                    }

                                    SettingsSection(strings.settings.networkHeader) {
                                        LabeledTextField(
                                            label = strings.settings.userAgentLabel,
                                            value = state.userAgentText,
                                            description = strings.settings.userAgentDesc,
                                            onValueChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateUserAgent(it)
                                                )
                                            }
                                        )
                                        NumberField(
                                            label = strings.settings.maxRedirectsLabel,
                                            value = state.maxRedirectsText,
                                            error = state.maxRedirectsError,
                                            description = strings.settings.maxRedirectsDesc,
                                            onValueChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateMaxRedirects(it)
                                                )
                                            }
                                        )
                                    }

                                    SettingsSection(strings.settings.torrentHeader) {
                                        NumberField(
                                            label = strings.settings.maxPeerConnectionsLabel,
                                            value = state.maxPeerConnectionsText,
                                            error = state.maxPeerConnectionsError,
                                            description = strings.settings.maxPeerConnectionsDesc,
                                            onValueChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateMaxPeerConnections(it)
                                                )
                                            }
                                        )
                                        CheckboxRow(
                                            text = strings.settings.seedingEnabledLabel,
                                            checked = seedingEnabled,
                                            onCheckedChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateEnableSeeding(it)
                                                )
                                            }
                                        )
                                        NumberField(
                                            label = strings.settings.seedTimeLimitLabel,
                                            value = state.seedTimeLimitMinutesText,
                                            error = state.seedTimeLimitMinutesError,
                                            enabled = seedingEnabled,
                                            fieldWidth = 150.dp,
                                            modifier = Modifier.padding(start = DependentIndent),
                                            onValueChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateSeedTimeLimitMinutes(it)
                                                )
                                            }
                                        )
                                    }
                                }

                                SettingsCategory.EXTENSIONS -> {
                                    val hStrings = strings.settings.handlers
                                    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

                                    SettingsSection(hStrings.supportedSitesHeader) {
                                        Text(
                                            text = hStrings.supportedSitesDesc,
                                            style = infoStyle,
                                            color = JewelTheme.globalColors.text.info
                                        )

                                        if (state.handlers.isEmpty()) {
                                            Text(hStrings.emptyHandlers)
                                        } else {
                                            state.handlers.forEach { handler ->
                                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        ExtensionIcon(
                                                            handler = handler.resolver,
                                                            registry = resolverRegistry,
                                                            size = 16.dp
                                                        )
                                                        CheckboxRow(
                                                            text = handler.displayName,
                                                            checked = handler.enabled,
                                                            onCheckedChange = { checked ->
                                                                screenModel.onEvent(
                                                                    SettingsEvent.ToggleHandler(handler.id, checked)
                                                                )
                                                            },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        Text(
                                                            text = if (handler.isPlugin) hStrings.pluginTag else hStrings.builtinTag,
                                                            style = infoStyle,
                                                            color = JewelTheme.globalColors.text.info
                                                        )
                                                        if (handler.isPlugin) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    screenModel.onEvent(
                                                                        SettingsEvent.RemoveExtension(handler.id)
                                                                    )
                                                                }
                                                            ) {
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
                                        }

                                        CheckboxRow(
                                            text = hStrings.alwaysAskLabel,
                                            checked = state.alwaysAskHandler,
                                            onCheckedChange = { checked ->
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateAlwaysAskHandler(checked)
                                                )
                                            }
                                        )
                                        Text(
                                            text = hStrings.alwaysAskDesc,
                                            style = infoStyle,
                                            color = JewelTheme.globalColors.text.info,
                                            modifier = Modifier.padding(start = DependentIndent)
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(onClick = { showJarPicker = true }) {
                                                Text(hStrings.installAction)
                                            }
                                            OutlinedButton(
                                                onClick = { screenModel.onEvent(SettingsEvent.ReloadHandlers) }
                                            ) {
                                                Text(hStrings.reloadAction)
                                            }
                                        }
                                    }

                                    SettingsSection(hStrings.extensionsDirHeader) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(text = hStrings.extensionsDirLabel)
                                            TextField(
                                                value = TextFieldValue(state.extensionDir),
                                                onValueChange = {},
                                                enabled = false,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Text(
                                                text = hStrings.extensionsDirDesc,
                                                style = infoStyle,
                                                color = JewelTheme.globalColors.text.info
                                            )
                                        }
                                    }
                                }

                                SettingsCategory.THEMES -> {
                                    val tStrings = strings.settings.themes
                                    val themeInfoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

                                    SettingsSection(tStrings.colorThemeHeader) {
                                        Text(
                                            text = tStrings.colorThemeDesc,
                                            style = themeInfoStyle,
                                            color = JewelTheme.globalColors.text.info
                                        )
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            state.themes.forEach { theme ->
                                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        RadioButtonRow(
                                                            text = theme.displayName,
                                                            selected = theme.active,
                                                            onClick = {
                                                                screenModel.onEvent(
                                                                    SettingsEvent.SelectTheme(theme.id)
                                                                )
                                                            },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        Text(
                                                            text = if (theme.isPlugin) tStrings.pluginTag else tStrings.builtinTag,
                                                            style = themeInfoStyle,
                                                            color = JewelTheme.globalColors.text.info
                                                        )
                                                        if (theme.isPlugin) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    screenModel.onEvent(
                                                                        SettingsEvent.RemoveTheme(theme.id)
                                                                    )
                                                                }
                                                            ) {
                                                                Text(tStrings.removeAction)
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = theme.description,
                                                        style = themeInfoStyle,
                                                        color = JewelTheme.globalColors.text.info,
                                                        modifier = Modifier.padding(start = DependentIndent)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    SettingsSection(tStrings.themesDirHeader) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(onClick = { showThemeJarPicker = true }) {
                                                Text(tStrings.installAction)
                                            }
                                            OutlinedButton(
                                                onClick = { screenModel.onEvent(SettingsEvent.ReloadThemes) }
                                            ) {
                                                Text(tStrings.reloadAction)
                                            }
                                        }
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(text = tStrings.themesDirLabel)
                                            TextField(
                                                value = TextFieldValue(state.themesDir),
                                                onValueChange = {},
                                                enabled = false,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Text(
                                                text = tStrings.themesDirDesc,
                                                style = themeInfoStyle,
                                                color = JewelTheme.globalColors.text.info
                                            )
                                        }
                                    }
                                }

                                SettingsCategory.FILES -> {
                                    SettingsSection(strings.settings.destinationHeader) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(text = strings.settings.defaultDownloadDirLabel)
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Read-only field: looks like the IDE's path fields and the
                                                // path can be selected and copied.
                                                TextField(
                                                    value = TextFieldValue(state.settings.defaultDownloadDir),
                                                    onValueChange = {},
                                                    enabled = false,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedButton(onClick = { showDirPicker = true }) {
                                                    Text(strings.common.browse)
                                                }
                                            }
                                        }

                                        CheckboxRow(
                                            text = strings.settings.askWhereToSaveLabel,
                                            checked = state.settings.askWhereToSave,
                                            onCheckedChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateAskWhereToSave(
                                                        it
                                                    )
                                                )
                                            }
                                        )
                                    }

                                    SettingsSection(strings.settings.fileConflictsHeader) {
                                        Text(text = strings.settings.fileConflictBehaviorLabel)

                                        val options = listOf(
                                            FileConflictBehavior.ASK to strings.settings.askOption,
                                            FileConflictBehavior.OVERWRITE to strings.settings.overwriteOption,
                                            FileConflictBehavior.SKIP to strings.settings.skipOption,
                                            FileConflictBehavior.RENAME to strings.settings.renameOption
                                        )
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            options.forEach { (behavior, label) ->
                                                RadioButtonRow(
                                                    text = label,
                                                    selected = state.settings.fileConflictBehavior == behavior,
                                                    onClick = {
                                                        screenModel.onEvent(
                                                            SettingsEvent.UpdateFileConflictBehavior(
                                                                behavior
                                                            )
                                                        )
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }

                                SettingsCategory.LOGS -> {
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
                                                    onClick = {
                                                        screenModel.onEvent(
                                                            SettingsEvent.UpdateLogLevel(level)
                                                        )
                                                    },
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
                                            onCheckedChange = {
                                                screenModel.onEvent(
                                                    SettingsEvent.UpdateFileLoggingEnabled(it)
                                                )
                                            }
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
                            }
                        }
                    }
                }
            }

            // Button bar: top divider only (the old border() drew a frame on all four sides).
            Column(modifier = Modifier.fillMaxWidth()) {
                Divider(Orientation.Horizontal)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JewelTheme.globalColors.panelBackground)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DefaultButton(
                        onClick = { screenModel.onEvent(SettingsEvent.SaveSettings) },
                        enabled = !downloadsHasError
                    ) {
                        Text(strings.common.ok)
                    }
                    OutlinedButton(
                        onClick = {
                            screenModel.onEvent(SettingsEvent.ResetSettings)
                            navigator.pop()
                        }
                    ) {
                        Text(strings.common.cancel)
                    }
                }
            }
        }

        if (showDirPicker) {
            FilePickerDialog(
                title = strings.settings.defaultDownloadDirLabel,
                mode = FilePickerMode.Directory,
                initialPath = remember(state.settings.defaultDownloadDir) {
                    runCatching { Path.of(state.settings.defaultDownloadDir) }.getOrNull()
                },
                onDismiss = { showDirPicker = false },
                onPick = { path ->
                    screenModel.onEvent(SettingsEvent.UpdateDefaultDownloadDir(path.toString()))
                }
            )
        }

        if (showJarPicker) {
            FilePickerDialog(
                title = strings.settings.handlers.installAction,
                mode = FilePickerMode.File,
                onDismiss = { showJarPicker = false },
                onPick = { path ->
                    showJarPicker = false
                    screenModel.onEvent(SettingsEvent.InstallExtension(path.toString()))
                },
                fileFilter = { it.toFile().extension.equals("jar", ignoreCase = true) }
            )
        }

        if (showThemeJarPicker) {
            FilePickerDialog(
                title = strings.settings.themes.installAction,
                mode = FilePickerMode.File,
                onDismiss = { showThemeJarPicker = false },
                onPick = { path ->
                    showThemeJarPicker = false
                    screenModel.onEvent(SettingsEvent.InstallTheme(path.toString()))
                },
                fileFilter = { it.toFile().extension.equals("jar", ignoreCase = true) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

/** IDE "titled separator": a heading followed by a hairline, then the section's controls. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.SemiBold)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(JewelTheme.globalColors.borders.normal)
            )
        }
        content()
    }
}

/** Left-hand category entry with rounded IDE-style selection and an optional error marker. */
@Composable
private fun SettingsNavItem(
    label: String,
    isSelected: Boolean,
    hasError: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = JewelTheme.simpleListItemStyle.colors

    val background = when {
        isSelected -> colors.backgroundSelectedActive
        hovered -> BlazeColors.hover
        else -> Color.Transparent
    }
    val contentColor = if (isSelected) colors.contentSelectedActive else Color.Unspecified

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hasError) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(BlazeColors.error)
                )
            }
        }
    }
}

/** Labeled numeric input with inline error and optional description. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun NumberField(
    label: String,
    value: TextFieldValue,
    error: String?,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    fieldWidth: Dp = 100.dp
) {
    val showError = enabled && error != null

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = if (enabled) Color.Unspecified else JewelTheme.globalColors.text.disabled
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            outline = if (showError) Outline.Error else Outline.None,
            modifier = Modifier.width(fieldWidth)
        )
        if (showError) {
            Text(
                text = error,
                color = BlazeColors.error,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = JewelTheme.globalColors.text.info
            )
        }
    }
}

/** Labeled free-text input (single line) with an optional description, e.g. the HTTP User-Agent. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun LabeledTextField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = if (enabled) Color.Unspecified else JewelTheme.globalColors.text.disabled
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        if (description != null) {
            Text(
                text = description,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = JewelTheme.globalColors.text.info
            )
        }
    }
}