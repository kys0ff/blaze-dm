package org.blaze.presentation.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.logging.LogConfigurator
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.DialogButton
import org.blaze.presentation.components.ToolWindowSearchField
import org.blaze.presentation.components.dialogButtonOrder
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.screens.settings.state.SettingsCategory
import org.blaze.presentation.screens.settings.state.SettingsState
import org.blaze.presentation.theme.BlazeIslandColors
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
 * How the hosted Settings content closes its own window. The content never learns what hosts it - the
 * IDE's dialog buttons and the window's native close button both mean "this Settings session is over",
 * and only the host knows which window to take away.
 */
internal val LocalSettingsDialogDismiss =
    staticCompositionLocalOf<() -> Unit> { error("Settings must be hosted by SettingsDialogWindow") }

/**
 * The Settings content: the category sidebar with its filter field, the pane hosting the selected
 * category section, and the OK / Cancel / Apply bar. Each category's controls live in its own file
 * (`SettingsGeneralSection`, `SettingsDownloadsSection`, ...); this file only wires the screen model
 * to the layout and owns the pickers the sections request through callbacks. It is hosted by
 * [org.blaze.presentation.application.components.SettingsDialogWindow] as a modal window, so there is
 * no header strip - the window's own title bar carries the title.
 */
class SettingsScreen : Screen {
    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun Content() {
        // TODO: This screen is a mess, needs to be broken up into smaller composable functions.
        // TODO: Also create a 'components' package for each screen's common components.
        val onDismiss = LocalSettingsDialogDismiss.current
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

        // The screen model owns when to leave; it just signals Close once OK/Cancel have done their
        // work, and removing the window is the host's business.
        LaunchedEffect(onDismiss) {
            screenModel.effects.collect { effect ->
                when (effect) {
                    SettingsEffect.Close -> onDismiss()
                }
            }
        }

        // The IDE's Settings dialog is one uniform surface, and now that Settings is a real window
        // its client area *is* that surface: the category list, the section and the footer already
        // paint the island color, so the root has to paint it too. A rounded Island wrapper would
        // only add a gap of unpainted pixels under the native frame.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BlazeIslandColors.island),
        ) {
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

/**
 * Left-hand category list, led by a search box; the Downloads entry shows an error dot when a field
 * is invalid.
 *
 * The IDE's Settings dialog opens with a field that prunes its tree. The category list here is flat and
 * tiny, so the same behavior costs one `filter` — typing keeps the rows whose label contains the query
 * and [SettingsNavItem] tints the matched run. The query is view state, deliberately not in the screen
 * model: nothing outside this sidebar reads it.
 *
 * The sidebar and [SettingsButtonBar] are `internal` (not private) so the headless screenshot harness
 * can render the screen's chrome skeleton — surface color, nav metrics, footer order — without wiring
 * the screen model up.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun SettingsSidebar(
    selected: SettingsCategory,
    hasDownloadsError: Boolean,
    strings: BlazeStrings,
    onSelect: (SettingsCategory) -> Unit
) {
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .width(BlazeLayout.SettingsNavWidth)
            .fillMaxHeight()
            .background(JewelTheme.globalColors.panelBackground)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        ToolWindowSearchField(
            query = query,
            placeholder = strings.settings.searchPlaceholder,
            clearDescription = strings.settings.clearSearch,
            onQueryChange = { query = it },
            fieldWidth = BlazeLayout.SettingsNavWidth,
        )
        Spacer(Modifier.height(6.dp))

        val categories = listOf(
            SettingsCategory.GENERAL to strings.settings.generalCategory,
            SettingsCategory.DOWNLOADS to strings.settings.downloadsCategory,
            SettingsCategory.FILES to strings.settings.filesCategory,
            SettingsCategory.EXTENSIONS to strings.settings.handlers.category,
            SettingsCategory.THEMES to strings.settings.themes.category,
            SettingsCategory.NOTIFICATIONS to strings.settings.notifications.category,
            SettingsCategory.LOGS to strings.settings.logsCategory
        )
        val matching = categories.filter { (_, label) ->
            query.isBlank() || label.contains(query, ignoreCase = true)
        }

        if (matching.isEmpty()) {
            Text(
                text = strings.settings.searchNoMatches,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        matching.forEach { (category, label) ->
            SettingsNavItem(
                label = label,
                isSelected = selected == category,
                hasError = category == SettingsCategory.DOWNLOADS && hasDownloadsError,
                query = query,
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
 *
 * The three are laid out by [dialogButtonOrder], the one platform switch: `OK, Cancel, Apply` on
 * Windows/Linux and `Cancel, Apply, OK` on macOS, where the default button belongs in the corner the
 * pointer rests in. Only the button *kind* is fixed per action, so OK stays accent-filled wherever it
 * lands.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun SettingsButtonBar(
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
            dialogButtonOrder(hasApply = true).forEach { button ->
                when (button) {
                    DialogButton.Ok -> DefaultButton(onClick = onOk, enabled = okEnabled) {
                        Text(strings.common.ok)
                    }

                    DialogButton.Cancel -> OutlinedButton(onClick = onCancel) {
                        Text(strings.common.cancel)
                    }

                    DialogButton.Apply -> OutlinedButton(onClick = onApply, enabled = applyEnabled) {
                        Text(strings.common.apply)
                    }
                }
            }
        }
    }
}
