package org.blaze.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.models.SelectedFile
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BackgroundTasksPopup
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.DownloadsColumn
import org.blaze.presentation.components.Island
import org.blaze.presentation.components.SidebarItem
import org.blaze.presentation.components.StatusBar
import org.blaze.presentation.components.ToolWindowStripe
import org.blaze.presentation.screens.downloads.DownloadFilter
import org.blaze.presentation.screens.downloads.DownloadSortField
import org.blaze.presentation.screens.downloads.SelectionActions
import org.blaze.presentation.screens.downloads.components.DownloadDetailsPanel
import org.blaze.presentation.screens.downloads.components.DownloadsFilterSortBar
import org.blaze.presentation.screens.downloads.components.DownloadsList
import org.blaze.presentation.screens.downloads.components.DownloadsToolbar
import org.blaze.presentation.screens.downloads.components.PauseRiskDialog
import org.blaze.presentation.screens.downloads.toCounts
import org.blaze.presentation.screens.settings.SettingsButtonBar
import org.blaze.presentation.screens.settings.SettingsSidebar
import org.blaze.presentation.screens.settings.state.SettingsCategory
import org.blaze.presentation.theme.BlazeIslandColors
import org.blaze.presentation.theme.BlazeTheme
import org.blaze.presentation.theme.BlazeTypography
import org.blaze.presentation.theme.blazeAmbientFrame
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.window.defaultTitleBarStyle
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * A headless screenshot harness. Renders the redesigned presentational composables offscreen with
 * Compose's [renderComposeScene] (Skia software raster — no window, no display) into PNGs under
 * `build/ui-screenshots/`, in both the light and dark Blaze themes.
 *
 * It targets the pure view composables (table, details pane, pause confirmation, background-tasks
 * popup) rather than the DI-wired full screens, so it exercises exactly the chrome the spec asks to
 * be checked — row height, selection colors, header, dividers, toolbar icon sizes, paddings — with
 * fake data and no engine. Run via `./gradlew :gui:uiScreenshots`.
 */
fun main() {
    val outDir = File("build/ui-screenshots").apply { mkdirs() }
    val density = Density(1f)
    val samples = sampleDownloads()

    fun shot(name: String, width: Int, height: Int, dark: Boolean, content: @Composable () -> Unit) {
        val image = renderComposeScene(width, height, density) {
            BlazeTheme(isDark = dark) {
                // The offscreen scene has no window to paint a background, so supply the same surface
                // token the real window uses; otherwise dark shots render light-on-transparent.
                Box(modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
                    content()
                }
            }
        }
        val data = image.encodeToData(EncodedImageFormat.PNG)
            ?: error("PNG encode failed for $name")
        File(outDir, "$name.png").writeBytes(data.bytes)
        image.close()
        println("[uiScreenshots] wrote $name.png ($width x $height, ${if (dark) "dark" else "light"})")
    }

    val columnWidths = DownloadsColumn.entries.associate { it.id to it.defaultWidth.value }

    for (dark in listOf(false, true)) {
        val suffix = if (dark) "dark" else "light"

        shot("01-downloads-table-$suffix", 940, 360, dark) {
            val listState = remember { LazyListState() }
            DownloadsList(
                downloads = samples,
                selectedIds = setOf("2"),
                listState = listState,
                columnWidths = columnWidths,
                sortField = DownloadSortField.NAME,
                sortAscending = true,
                onEvent = {},
                onRemoveRequested = {},
                onPauseRequested = {},
                onResumeRequested = {},
                onShowDetailsRequested = {},
                onSelect = { _, _, _ -> },
                onSelectAll = {},
                hideResumeForQueued = false,
                modifier = Modifier.fillMaxSize(),
            )
        }

        shot("02-details-general-$suffix", 380, 440, dark) {
            DownloadDetailsPanel(
                download = samples.first { it.id == "5" },
                onHide = {},
                modifier = Modifier.fillMaxSize(),
            )
        }

        shot("03-details-files-$suffix", 380, 440, dark) {
            DownloadDetailsPanel(
                download = samples.first { it.id == "6" },
                onHide = {},
                modifier = Modifier.fillMaxSize(),
            )
        }

        shot("04-details-empty-$suffix", 380, 200, dark) {
            DownloadDetailsPanel(
                download = null,
                onHide = {},
                modifier = Modifier.fillMaxSize(),
            )
        }

        shot("05-pause-dialog-$suffix", 520, 260, dark) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PauseRiskDialog(
                    download = samples.first { it.id == "5" },
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        shot("06-background-tasks-$suffix", 560, 420, dark) {
            BackgroundTasksPopup(
                running = samples.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED },
                onPause = {},
                onCancel = {},
                onDismiss = {},
            )
        }

        // Section 4: the tool-window toolbar (search field + action cluster) over the IDE-style
        // filter chips. Rendered in the no-selection state so the search field and per-command
        // buttons show; the bulk cluster swaps in when a selection exists.
        shot("07-toolbar-filter-$suffix", 940, 96, dark) {
            Column(modifier = Modifier.fillMaxSize()) {
                DownloadsToolbar(
                    hasActiveDownloads = true,
                    hasPausedDownloads = true,
                    hasCompletedDownloads = true,
                    hasSelection = false,
                    selectedCount = 0,
                    selectionActions = SelectionActions.of(samples),
                    searchQuery = "",
                    snailModeEnabled = false,
                    searchFocusRequester = remember { FocusRequester() },
                    onEvent = {},
                    onAddDownload = {},
                    onImportPortable = {},
                    onClearCompleted = {},
                    onSelectAll = {},
                    onClearSelection = {},
                    onBulkPause = {},
                    onBulkResume = {},
                    onBulkCancel = {},
                    onBulkRemove = {},
                    detailsVisible = true,
                    onToggleDetails = {},
                )
                DownloadsFilterSortBar(
                    filter = DownloadFilter.ALL,
                    counts = samples.toCounts(),
                    onEvent = {},
                )
            }
        }

        // The same toolbar with a selection, i.e. the bulk cluster that replaces the search field:
        // pause, resume, the plain red stop square and remove. Fixture for the stop glyph (#4).
        shot("08-toolbar-selection-$suffix", 940, 40, dark) {
            DownloadsToolbar(
                hasActiveDownloads = true,
                hasPausedDownloads = true,
                hasCompletedDownloads = true,
                hasSelection = true,
                selectedCount = 3,
                selectionActions = SelectionActions.of(samples),
                searchQuery = "",
                snailModeEnabled = false,
                searchFocusRequester = remember { FocusRequester() },
                onEvent = {},
                onAddDownload = {},
                onImportPortable = {},
                onClearCompleted = {},
                onSelectAll = {},
                onClearSelection = {},
                onBulkPause = {},
                onBulkResume = {},
                onBulkCancel = {},
                onBulkRemove = {},
                detailsVisible = false,
                onToggleDetails = {},
            )
        }

        // Full Islands shell: the frame layer *with* its ambient glow, the left stripe (with a
        // selected gray button), the downloads + details islands separated by a gap, and the status
        // bar below on the frame. This is the landmark source for the delta-check script
        // (frame/island/gap/radius/stripe + the gradient tint landmarks).
        shot("10-shell-$suffix", 1100, 720, dark) {
            // The chrome's text styles are resolved here and their real FontWeight printed, so the
            // "regular weight everywhere" rule is measured on every run instead of being asserted.
            val fontReport = BlazeTypography.auditReport()
            remember(fontReport) { fontReport.trimEnd().lines().forEach { println(it) } }
            Column(modifier = Modifier.fillMaxSize().blazeAmbientFrame(anchorY = 0.dp)) {
                // The real window paints the glow behind a transparent Islands title bar, so an
                // empty strip of that exact height reproduces the reference's title band: the
                // gradient's horizontal peak lives inside it, and the stripe/gap below shows the
                // vertical decay. The headless scene has no decorations of its own.
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(JewelTheme.defaultTitleBarStyle.metrics.height)
                )
                Row(modifier = Modifier.weight(1f)) {
                    ToolWindowStripe(
                        items = listOf(
                            SidebarItem("Downloads", AllIconsKeys.Actions.Download, "downloads"),
                            SidebarItem("Settings", AllIconsKeys.General.Settings, "settings", pinned = true),
                        ),
                        selectedId = "downloads",
                        onItemSelected = {},
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(BlazeLayout.IslandGap),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(BlazeLayout.IslandGap),
                        ) {
                            Island(modifier = Modifier.weight(1f)) {
                                val listState = remember { LazyListState() }
                                DownloadsList(
                                    downloads = samples,
                                    selectedIds = setOf("2"),
                                    listState = listState,
                                    columnWidths = columnWidths,
                                    sortField = DownloadSortField.NAME,
                                    sortAscending = true,
                                    onEvent = {},
                                    onRemoveRequested = {},
                                    onPauseRequested = {},
                                    onResumeRequested = {},
                                    onShowDetailsRequested = {},
                                    onSelect = { _, _, _ -> },
                                    onSelectAll = {},
                                    hideResumeForQueued = false,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Island(modifier = Modifier.weight(0.45f)) {
                                DownloadDetailsPanel(
                                    download = samples.first { it.id == "5" },
                                    onHide = {},
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                StatusBar(info = "1 downloading", throughput = "2.7 MB/s", isConnected = true)
            }
        }

        // The Settings window's content, rendered from the screen's own scaffolding pieces. It is the
        // fixture for three fixes: the whole surface must be one color from list to footer (#10 - the
        // middle pane is deliberately given no background of its own, so a leak of another shade would
        // show here), the nav's search box and row metrics (#13), and the platform-ordered
        // OK/Cancel/Apply footer (#11). Fake strings and no screen model, exactly like the other shots.
        shot("11-settings-shell-$suffix", 900, 520, dark) {
            val strings = blazeStrings
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BlazeIslandColors.island),
            ) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SettingsSidebar(
                        selected = SettingsCategory.GENERAL,
                        hasDownloadsError = false,
                        strings = strings,
                        onSelect = {},
                    )
                    Divider(Orientation.Vertical, Modifier.fillMaxHeight())
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(strings.settings.generalCategory)
                        Text(
                            text = strings.settings.placeholder,
                            color = JewelTheme.globalColors.text.info,
                        )
                    }
                }
                SettingsButtonBar(
                    okEnabled = true,
                    applyEnabled = true,
                    onOk = {},
                    onApply = {},
                    onCancel = {},
                    strings = strings,
                )
            }
        }
    }

    println("[uiScreenshots] done -> ${outDir.absolutePath}")
}

private const val MB = 1024L * 1024L
private const val GB = 1024L * MB

private fun sampleDownloads(): List<Download> {
    val now = System.currentTimeMillis()
    fun dl(
        id: String,
        name: String,
        url: String,
        total: Long?,
        done: Long,
        speed: Long,
        state: DownloadState,
        peers: Int = 0,
        resume: Boolean? = null,
        eta: Long? = null,
        files: List<SelectedFile>? = null,
        savePath: String = "/home/user/Downloads/$name",
    ) = Download(
        id = id,
        name = name,
        url = url,
        totalSize = total,
        downloadedSize = done,
        speed = speed,
        eta = eta,
        peers = peers,
        state = state,
        addedAt = now,
        scheduledAt = null,
        savePath = savePath,
        error = null,
        selectedFiles = files,
        resumeSupported = resume,
    )

    return listOf(
        dl("1", "ubuntu-24.04-desktop.iso", "https://releases.example.com/ubuntu-24.04.iso",
            total = 5 * GB, done = 2400 * MB, speed = 4 * MB, state = DownloadState.DOWNLOADING,
            resume = true, eta = 760),
        dl("2", "big-buck-bunny.torrent", "magnet:?xt=urn:btih:bbb000",
            total = 1500 * MB, done = 0, speed = 0, state = DownloadState.DOWNLOADING, peers = 0),
        dl("3", "trial-setup.exe", "https://vendor.example.com/trial.exe",
            total = 800 * MB, done = 260 * MB, speed = 0, state = DownloadState.PAUSED, resume = false),
        dl("4", "dataset-part2.zip", "https://archive.example.com/dataset-part2.zip",
            total = 3 * GB, done = 0, speed = 0, state = DownloadState.QUEUED, resume = true),
        dl("5", "hotfix-bundle.zip", "https://mirror.example.com/hotfix-bundle.zip",
            total = 500 * MB, done = 220 * MB, speed = 1536 * 1024, state = DownloadState.DOWNLOADING,
            resume = false, eta = 180),
        dl("6", "photos-2026 (torrent)", "magnet:?xt=urn:btih:ph26",
            total = 640 * MB, done = 640 * MB, speed = 900 * 1024, state = DownloadState.SEEDING, peers = 8,
            files = listOf(
                SelectedFile("/home/user/Downloads/photos-2026/jan.jpg", 4 * MB, 0),
                SelectedFile("/home/user/Downloads/photos-2026/feb.jpg", 6 * MB, 1),
                SelectedFile("/home/user/Downloads/photos-2026/mar.raw", 120 * MB, 2),
            )),
        dl("7", "broken-archive.tar.gz", "https://down.example.com/broken.tar.gz",
            total = 120 * MB, done = 40 * MB, speed = 0, state = DownloadState.FAILED, resume = null),
    )
}
