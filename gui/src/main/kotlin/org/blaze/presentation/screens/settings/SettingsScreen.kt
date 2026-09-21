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
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.logging.LogConfigurator
import org.blaze.presentation.components.ExtensionIcon
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.screens.downloads.DownloadsScreen
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.screens.settings.state.HandlerUiState
import org.blaze.presentation.screens.settings.state.SettingsCategory
import org.blaze.presentation.screens.settings.state.SettingsState
import org.blaze.presentation.screens.settings.state.ThemeUiState
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

        val onEvent: (SettingsEvent) -> Unit = screenModel::onEvent
        // Errors on disabled fields don't block the buttons (see SettingsState.hasValidationError).
        val hasError = state.hasValidationError
        // Apply only makes sense when there is something uncommitted to write.
        val canApply = state.isDirty && !hasError

        // All categories share one scroll state, so start each one at the top.
        LaunchedEffect(state.currentCategory) { scrollState.scrollTo(0) }

        // The screen model owns when to leave; it just signals Close after OK/Cancel have done their work.
        LaunchedEffect(Unit) {
            screenModel.effects.collect { effect ->
                when (effect) {
                    SettingsEffect.Close -> navigator.replaceAll(DownloadsScreen())
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            ToolWindowHeader(title = strings.settings.title)

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SettingsSidebar(
                    selected = state.currentCategory,
                    hasDownloadsError = hasError,
                    strings = strings,
                    onSelect = { onEvent(SettingsEvent.ChangeCategory(it)) }
                )

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
                            SettingsContent(
                                state = state,
                                strings = strings,
                                onEvent = onEvent,
                                resolverRegistry = resolverRegistry,
                                logConfigurator = logConfigurator,
                                onBrowseDownloadDir = { showDirPicker = true },
                                onInstallHandler = { showJarPicker = true },
                                onInstallTheme = { showThemeJarPicker = true }
                            )
                        }
                    }
                }
            }

            SettingsButtonBar(
                okEnabled = !hasError,
                applyEnabled = canApply,
                onOk = { onEvent(SettingsEvent.Ok) },
                onApply = { onEvent(SettingsEvent.Apply) },
                onCancel = { onEvent(SettingsEvent.Cancel) },
                strings = strings
            )
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
                    onEvent(SettingsEvent.UpdateDefaultDownloadDir(path.toString()))
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
                    onEvent(SettingsEvent.InstallExtension(path.toString()))
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
                    onEvent(SettingsEvent.InstallTheme(path.toString()))
                },
                fileFilter = { it.toFile().extension.equals("jar", ignoreCase = true) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Layout scaffolding
// ---------------------------------------------------------------------------

/** Left-hand category list; the Downloads entry shows an error dot when a field is invalid. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun SettingsSidebar(
    selected: SettingsCategory,
    hasDownloadsError: Boolean,
    strings: BlazeStrings,
    onSelect: (SettingsCategory) -> Unit
) {
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
                isSelected = selected == category,
                hasError = category == SettingsCategory.DOWNLOADS && hasDownloadsError,
                onClick = { onSelect(category) }
            )
        }
    }
}

/** Dispatches to the section composables for the currently selected category. */
@Composable
private fun SettingsContent(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit,
    resolverRegistry: LinkResolverRegistry,
    logConfigurator: LogConfigurator,
    onBrowseDownloadDir: () -> Unit,
    onInstallHandler: () -> Unit,
    onInstallTheme: () -> Unit
) {
    when (state.currentCategory) {
        SettingsCategory.GENERAL -> GeneralSection(state, strings, onEvent)
        SettingsCategory.DOWNLOADS -> DownloadsSection(state, strings, onEvent)
        SettingsCategory.FILES -> FilesSection(state, strings, onEvent, onBrowseDownloadDir)
        SettingsCategory.EXTENSIONS ->
            ExtensionsSection(state, strings, onEvent, resolverRegistry, onInstallHandler)
        SettingsCategory.THEMES -> ThemesSection(state, strings, onEvent, onInstallTheme)
        SettingsCategory.LOGS -> LogsSection(state, strings, onEvent, logConfigurator)
    }
}

/**
 * OK commits and closes, Apply commits and stays, Cancel discards and closes. Committing is what
 * finally writes the buffered draft to the repositories/registries.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun SettingsButtonBar(
    okEnabled: Boolean,
    applyEnabled: Boolean,
    onOk: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    strings: BlazeStrings
) {
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
            DefaultButton(onClick = onOk, enabled = okEnabled) {
                Text(strings.common.ok)
            }
            OutlinedButton(onClick = onApply, enabled = applyEnabled) {
                Text(strings.common.apply)
            }
            OutlinedButton(onClick = onCancel) {
                Text(strings.common.cancel)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Category sections
// ---------------------------------------------------------------------------

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun GeneralSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit
) {
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

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
                    onClick = { onEvent(SettingsEvent.UpdateThemeMode(mode)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    SettingsSection(strings.settings.systemIntegrationHeader) {
        val trayEnabled = state.appSettings.trayEnabled
        // Each option only appears when the environment can actually honour it
        // (no tray on GNOME without an indicator extension, no autostart on odd OSes).
        if (state.traySupported) {
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
            // Dependent option: shown only while the tray itself is on.
            if (trayEnabled) {
                Column(modifier = Modifier.padding(start = DependentIndent)) {
                    CheckboxRow(
                        text = strings.settings.minimizeToTrayLabel,
                        checked = state.appSettings.minimizeToTrayOnClose,
                        onCheckedChange = { onEvent(SettingsEvent.UpdateMinimizeToTrayOnClose(it)) }
                    )
                }
            }
        }
        if (state.autoStartSupported) {
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

    SettingsSection(strings.settings.startupHeader) {
        CheckboxRow(
            text = strings.settings.resumeOnStartupLabel,
            checked = state.settings.resumeDownloadsOnStartup,
            onCheckedChange = { onEvent(SettingsEvent.UpdateResumeDownloadsOnStartup(it)) }
        )
        CheckboxRow(
            text = strings.settings.startQueuedOnStartupLabel,
            checked = state.settings.startQueuedOnStartup,
            onCheckedChange = { onEvent(SettingsEvent.UpdateStartQueuedOnStartup(it)) }
        )
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun DownloadsSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit
) {
    val speedLimitEnabled = state.settings.globalSpeedLimitEnabled
    val autoRetryEnabled = state.settings.autoRetryFailed
    val seedingEnabled = state.settings.enableSeeding

    SettingsSection(strings.settings.concurrencyHeader) {
        NumberField(
            label = strings.settings.maxConcurrentDownloadsLabel,
            value = state.maxConcurrentDownloadsText,
            error = state.maxConcurrentDownloadsError,
            description = strings.settings.maxConcurrentDownloadsDesc,
            onValueChange = { onEvent(SettingsEvent.UpdateMaxConcurrentDownloads(it)) }
        )
        NumberField(
            label = strings.settings.maxConnectionsLabel,
            value = state.maxConnectionsPerDownloadText,
            error = state.maxConnectionsPerDownloadError,
            description = strings.settings.maxConnectionsDesc,
            onValueChange = { onEvent(SettingsEvent.UpdateMaxConnectionsPerDownload(it)) }
        )
    }

    SettingsSection(strings.settings.bandwidthHeader) {
        CheckboxRow(
            text = strings.settings.speedLimitEnabledLabel,
            checked = speedLimitEnabled,
            onCheckedChange = { onEvent(SettingsEvent.UpdateSpeedLimitEnabled(it)) }
        )
        // Dependent option: indented and disabled (not hidden) when off, like IDE settings,
        // so the layout doesn't jump.
        NumberField(
            label = strings.settings.speedLimitLabel,
            value = state.globalSpeedLimitKbpsText,
            error = state.globalSpeedLimitKbpsError,
            enabled = speedLimitEnabled,
            fieldWidth = 150.dp,
            modifier = Modifier.padding(start = DependentIndent),
            onValueChange = { onEvent(SettingsEvent.UpdateSpeedLimitKbps(it)) }
        )
    }

    SettingsSection(strings.settings.retryHeader) {
        CheckboxRow(
            text = strings.settings.autoRetryLabel,
            checked = autoRetryEnabled,
            onCheckedChange = { onEvent(SettingsEvent.UpdateAutoRetryFailed(it)) }
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
                onValueChange = { onEvent(SettingsEvent.UpdateMaxRetries(it)) }
            )
            NumberField(
                label = strings.settings.retryDelayLabel,
                value = state.retryDelaySecondsText,
                error = state.retryDelaySecondsError,
                enabled = autoRetryEnabled,
                onValueChange = { onEvent(SettingsEvent.UpdateRetryDelaySeconds(it)) }
            )
        }
        CheckboxRow(
            text = strings.settings.exponentialBackoffLabel,
            checked = state.settings.exponentialBackoff,
            enabled = autoRetryEnabled,
            onCheckedChange = { onEvent(SettingsEvent.UpdateExponentialBackoff(it)) },
            modifier = Modifier.padding(start = DependentIndent)
        )
    }

    SettingsSection(strings.settings.networkHeader) {
        LabeledTextField(
            label = strings.settings.userAgentLabel,
            value = state.userAgentText,
            description = strings.settings.userAgentDesc,
            onValueChange = { onEvent(SettingsEvent.UpdateUserAgent(it)) }
        )
        NumberField(
            label = strings.settings.maxRedirectsLabel,
            value = state.maxRedirectsText,
            error = state.maxRedirectsError,
            description = strings.settings.maxRedirectsDesc,
            onValueChange = { onEvent(SettingsEvent.UpdateMaxRedirects(it)) }
        )
    }

    SettingsSection(strings.settings.torrentHeader) {
        NumberField(
            label = strings.settings.maxPeerConnectionsLabel,
            value = state.maxPeerConnectionsText,
            error = state.maxPeerConnectionsError,
            description = strings.settings.maxPeerConnectionsDesc,
            onValueChange = { onEvent(SettingsEvent.UpdateMaxPeerConnections(it)) }
        )
        CheckboxRow(
            text = strings.settings.seedingEnabledLabel,
            checked = seedingEnabled,
            onCheckedChange = { onEvent(SettingsEvent.UpdateEnableSeeding(it)) }
        )
        NumberField(
            label = strings.settings.seedTimeLimitLabel,
            value = state.seedTimeLimitMinutesText,
            error = state.seedTimeLimitMinutesError,
            enabled = seedingEnabled,
            fieldWidth = 150.dp,
            modifier = Modifier.padding(start = DependentIndent),
            onValueChange = { onEvent(SettingsEvent.UpdateSeedTimeLimitMinutes(it)) }
        )
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun FilesSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit,
    onBrowseDownloadDir: () -> Unit
) {
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
                OutlinedButton(onClick = onBrowseDownloadDir) {
                    Text(strings.common.browse)
                }
            }
        }

        CheckboxRow(
            text = strings.settings.askWhereToSaveLabel,
            checked = state.settings.askWhereToSave,
            onCheckedChange = { onEvent(SettingsEvent.UpdateAskWhereToSave(it)) }
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
                    onClick = { onEvent(SettingsEvent.UpdateFileConflictBehavior(behavior)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ExtensionsSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit,
    resolverRegistry: LinkResolverRegistry,
    onInstallHandler: () -> Unit
) {
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
                HandlerRow(
                    handler = handler,
                    enabled = state.effectiveHandlerEnabled(handler),
                    infoStyle = infoStyle,
                    strings = strings,
                    registry = resolverRegistry,
                    onToggle = { checked -> onEvent(SettingsEvent.ToggleHandler(handler.id, checked)) },
                    onRemove = { onEvent(SettingsEvent.RemoveExtension(handler.id)) }
                )
            }
        }

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

    SettingsSection(hStrings.extensionsDirHeader) {
        ReadOnlyPathField(
            label = hStrings.extensionsDirLabel,
            path = state.extensionDir,
            description = hStrings.extensionsDirDesc,
            infoStyle = infoStyle
        )
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
    onRemove: () -> Unit
) {
    val hStrings = strings.settings.handlers
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

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ThemesSection(
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
    infoStyle: androidx.compose.ui.text.TextStyle,
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

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun LogsSection(
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

/** Label + read-only, copyable path field with an optional description. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ReadOnlyPathField(
    label: String,
    path: String,
    description: String,
    infoStyle: androidx.compose.ui.text.TextStyle
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label)
        TextField(
            value = TextFieldValue(path),
            onValueChange = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = description,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info
        )
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
