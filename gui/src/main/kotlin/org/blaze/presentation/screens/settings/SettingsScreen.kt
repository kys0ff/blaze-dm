package org.blaze.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.logging.LogConfigurator
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.screens.downloads.DownloadsScreen
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.screens.settings.state.SettingsCategory
import org.blaze.presentation.screens.settings.state.SettingsState
import org.blaze.resolver.core.LinkResolverRegistry
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.koin.compose.koinInject
import java.nio.file.Path

/**
 * The settings screen shell: header, category sidebar, the content pane hosting the selected
 * category section, and the OK/Apply/Cancel bar. Each category's controls live in its own file
 * (`SettingsGeneralSection`, `SettingsDownloadsSection`, ...); this file only wires the screen
 * model to the layout and owns the modal pickers the sections request through callbacks.
 */
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
            SettingsCategory.NOTIFICATIONS to strings.settings.notifications.category,
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
        SettingsCategory.NOTIFICATIONS -> NotificationsSection(state, strings, onEvent)
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
