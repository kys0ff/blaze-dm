package org.blaze.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import kotlinx.coroutines.delay
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.logging.LogConfigurator
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.notifications.LocalNotifications
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.screens.settings.components.NothingFoundPanel
import org.blaze.presentation.screens.settings.components.SettingsButtonBar
import org.blaze.presentation.screens.settings.components.SettingsSidebar
import org.blaze.presentation.screens.settings.components.settingsNavCategories
import org.blaze.presentation.screens.settings.state.SettingsCategory
import org.blaze.presentation.screens.settings.state.SettingsState
import org.blaze.presentation.search.SearchController
import org.blaze.presentation.theme.BlazeIslandColors
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.theming.core.ThemeRegistry
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.koin.compose.koinInject
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

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
        val onDismiss = LocalSettingsDialogDismiss.current
        val screenModel = koinScreenModel<SettingsScreenModel>()
        val state by screenModel.state.collectAsState()
        val strings = blazeStrings
        val resolverRegistry = koinInject<LinkResolverRegistry>()
        val themeRegistry = koinInject<ThemeRegistry>()
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

        // The live Settings search, lifted to the whole screen so the sidebar (which prunes pages) and the
        // content pane (which hides rows through [SettingRow]) read one query. Empty means "no filtering".
        var query by remember { mutableStateOf("") }
        val filter = SettingsFilter(query)
        // The page showing before the user started searching, restored the instant the query is cleared.
        var categoryBeforeSearch by remember { mutableStateOf<SettingsCategory?>(null) }
        // The row Search Everywhere asked us to jump to, painted with a flash while this is non-null.
        val searchController = koinInject<SearchController>()
        var flashId by remember { mutableStateOf<String?>(null) }

        val categories = settingsNavCategories(strings)

        // All categories share one scroll state, so start each one at the top.
        LaunchedEffect(state.currentCategory) { scrollState.scrollTo(0) }

        // The screen model owns when to leave; it just signals Close once OK/Cancel have done their
        // work, and removing the window is the host's business. Balloons go to the main frame's
        // notification host (Settings' own DialogWindow has no host surface), and a message emitted
        // while Settings is closing still shows because the frame outlives the dialog.
        val notifications = LocalNotifications.current
        LaunchedEffect(onDismiss) {
            screenModel.effects.collect { effect ->
                when (effect) {
                    SettingsEffect.Close -> onDismiss()
                    is SettingsEffect.Message -> notifications?.info(effect.text)
                }
            }
        }

        // Jump-and-flash: when a Search Everywhere result opened Settings for a specific option, select its
        // page and flash the row, then clear the request so it fires only once.
        LaunchedEffect(Unit) {
            val target = searchController.pendingSettingFocus
            if (target != null) {
                SettingsRegistry.find(target)?.let { option ->
                    onEvent(SettingsEvent.ChangeCategory(option.category))
                    flashId = target
                }
                searchController.pendingSettingFocus = null
            }
        }
        LaunchedEffect(flashId) {
            if (flashId != null) {
                delay(FLASH_MS.milliseconds)
                flashId = null
            }
        }

        // Prune-to-first / restore-on-clear, the IDE's behavior: a query that leaves the current page with
        // no matches jumps to the first page that has one; clearing returns to the page the user was on.
        LaunchedEffect(query) {
            if (query.isNotBlank()) {
                if (categoryBeforeSearch == null) categoryBeforeSearch = state.currentCategory
                val matchingPages = categories.map { it.first }
                    .filter { SettingsRegistry.categoryMatches(it, query, strings) }
                if (matchingPages.isNotEmpty() && state.currentCategory !in matchingPages) {
                    onEvent(SettingsEvent.ChangeCategory(matchingPages.first()))
                }
            } else {
                val restore = categoryBeforeSearch
                categoryBeforeSearch = null
                if (restore != null && restore != state.currentCategory) {
                    onEvent(SettingsEvent.ChangeCategory(restore))
                }
            }
        }

        val anyMatch = query.isBlank() ||
            categories.any { SettingsRegistry.categoryMatches(it.first, query, strings) }

        // The IDE's Settings dialog is one uniform surface, and now that Settings is a real window
        // its client area *is* that surface: the category list, the section and the footer already
        // paint the island color, so the root has to paint it too. A rounded Island wrapper would
        // only add a gap of unpainted pixels under the native frame.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BlazeIslandColors.island)
                .onPreviewKeyEvent { event ->
                    // Escape clears an active query first and only closes the window on the second press.
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        if (query.isNotBlank()) {
                            query = ""
                        } else {
                            onDismiss()
                        }
                        true
                    } else {
                        false
                    }
                },
        ) {
            CompositionLocalProvider(
                LocalSettingsFilter provides filter,
                LocalSettingsFlashId provides flashId,
            ) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SettingsSidebar(
                        selected = state.currentCategory,
                        hasDownloadsError = hasError,
                        strings = strings,
                        query = query,
                        onQueryChange = { query = it },
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
                            if (!anyMatch) {
                                NothingFoundPanel(strings = strings, onClear = { query = "" })
                            } else {
                                Column(
                                    modifier = Modifier.widthIn(max = BlazeLayout.FormMaxWidth),
                                    verticalArrangement = Arrangement.spacedBy(24.dp)
                                ) {
                                    SettingsContent(
                                        state = state,
                                        strings = strings,
                                        onEvent = onEvent,
                                        resolverRegistry = resolverRegistry,
                                        themeRegistry = themeRegistry,
                                        logConfigurator = logConfigurator,
                                        onBrowseDownloadDir = { showDirPicker = true },
                                        onInstallHandler = { showJarPicker = true },
                                        onInstallTheme = { showThemeJarPicker = true }
                                    )
                                }
                            }
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

        SettingsPickerDialogs(
            state = state,
            strings = strings,
            onEvent = onEvent,
            showDirPicker = showDirPicker,
            onDirPickerDismiss = { showDirPicker = false },
            showJarPicker = showJarPicker,
            onJarPickerDismiss = { showJarPicker = false },
            showThemeJarPicker = showThemeJarPicker,
            onThemeJarPickerDismiss = { showThemeJarPicker = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Screen-local helpers
// ---------------------------------------------------------------------------

/** How long a jump-from-Search-Everywhere keeps the target row flashed. */
private const val FLASH_MS = 1400L

/** Dispatches to the section composables for the currently selected category. */
@Composable
private fun SettingsContent(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit,
    resolverRegistry: LinkResolverRegistry,
    themeRegistry: ThemeRegistry,
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
        SettingsCategory.THEMES -> ThemesSection(state, strings, themeRegistry, onEvent, onInstallTheme)
        SettingsCategory.NOTIFICATIONS -> NotificationsSection(state, strings, onEvent)
        SettingsCategory.SHORTCUTS -> ShortcutsSection(state, strings, onEvent)
        SettingsCategory.LOGS -> LogsSection(state, strings, onEvent, logConfigurator)
    }
}

/**
 * The three file/folder pickers the sections request through their browse & install callbacks. They
 * are kept out of [Content] so the screen body reads as a single composition; each picker is driven by
 * a visible flag plus a dismiss callback, and forwards the chosen path back as a [SettingsEvent].
 */
@Composable
private fun SettingsPickerDialogs(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit,
    showDirPicker: Boolean,
    onDirPickerDismiss: () -> Unit,
    showJarPicker: Boolean,
    onJarPickerDismiss: () -> Unit,
    showThemeJarPicker: Boolean,
    onThemeJarPickerDismiss: () -> Unit,
) {
    if (showDirPicker) {
        FilePickerDialog(
            title = strings.settings.defaultDownloadDirLabel,
            mode = FilePickerMode.Directory,
            initialPath = remember(state.settings.defaultDownloadDir) {
                runCatching { Path.of(state.settings.defaultDownloadDir) }.getOrNull()
            },
            onDismiss = onDirPickerDismiss,
            onPick = { path ->
                onEvent(SettingsEvent.UpdateDefaultDownloadDir(path.toString()))
            }
        )
    }

    if (showJarPicker) {
        FilePickerDialog(
            title = strings.settings.handlers.installAction,
            mode = FilePickerMode.File,
            onDismiss = onJarPickerDismiss,
            onPick = { path ->
                onJarPickerDismiss()
                onEvent(SettingsEvent.InstallExtension(path.toString()))
            },
            fileFilter = { it.toFile().extension.equals("jar", ignoreCase = true) }
        )
    }

    if (showThemeJarPicker) {
        FilePickerDialog(
            title = strings.settings.themes.installAction,
            mode = FilePickerMode.File,
            onDismiss = onThemeJarPickerDismiss,
            onPick = { path ->
                onThemeJarPickerDismiss()
                onEvent(SettingsEvent.InstallTheme(path.toString()))
            },
            fileFilter = { it.toFile().extension.equals("jar", ignoreCase = true) }
        )
    }
}
