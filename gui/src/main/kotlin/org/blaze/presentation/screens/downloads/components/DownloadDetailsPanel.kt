package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.blaze.presentation.screens.downloads.DetailsResize
import org.blaze.presentation.screens.downloads.DetailsViewMode
import org.blaze.presentation.screens.downloads.DownloadsEvent
import org.blaze.presentation.screens.downloads.ResumeSupport
import org.blaze.presentation.screens.downloads.isUnresumableInProgress
import org.blaze.presentation.screens.downloads.isWaitingForPeers
import org.blaze.presentation.screens.downloads.pauseLosesProgress
import org.blaze.presentation.screens.downloads.percentDone
import org.blaze.presentation.screens.downloads.resumeSupport
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.util.formatDuration
import org.blaze.presentation.util.formatSize
import org.blaze.presentation.util.formatSpeed
import org.blaze.presentation.util.toFriendlyMessage
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import org.jetbrains.jewel.ui.typography
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DetailLabelWidth = 120.dp

/**
 * The Details tool window shown beside the downloads list. It renders the read-only summary of the
 * single selected download (or an empty-state hint when the selection is not exactly one row) and,
 * crucially, surfaces every advisory the compact table row can only hint at: "cannot resume",
 * "waiting for peers", the pause cost, and a missing file all repeat here in full. Tabs mirror the
 * IDE's Run tool window — General always, Files when the source really is a multi-file torrent.
 */
@Composable
fun DownloadDetailsPanel(
    download: Download?,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    viewMode: DetailsViewMode = DetailsViewMode.DOCK_PINNED,
    moveTo: DetailsMoveTo = DetailsMoveTo.RIGHT_TOP,
    groupTabs: Boolean = false,
    onEvent: (DownloadsEvent) -> Unit = {},
) {
    val strings = blazeStrings
    val pane = strings.downloads.detailsPane
    Column(modifier = modifier.fillMaxSize()) {
        ToolWindowHeader(
            title = strings.downloads.dialogs.details.title,
            actions = {
                DetailsOptionsButton(
                    options = pane.options,
                    viewMode = viewMode,
                    moveTo = moveTo,
                    groupTabs = groupTabs,
                    onEvent = onEvent,
                )
                ToolbarIconButton(
                    key = AllIconsKeys.General.Close,
                    tooltip = pane.hide,
                    onClick = onHide,
                )
            },
        )
        if (download == null) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(BlazeLayout.ContentPaddingLarge),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = pane.emptyHint,
                    style = JewelTheme.typography.small,
                    color = JewelTheme.globalColors.text.info,
                )
            }
        } else {
            DetailsBody(
                download = download,
                generalLabel = pane.tabGeneral,
                filesLabel = pane.tabFiles,
                groupTabs = groupTabs,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The Details pane's tool-window gear, mirroring an IDE's: it opens a menu to group the tabs and to
 * pick the view mode, dock position and size. The choices are routed back as [DownloadsEvent]s, so
 * the screen model owns persistence while this stays purely presentational.
 */
@Composable
private fun DetailsOptionsButton(
    options: BlazeStrings.Downloads.DetailsPane.ToolWindowOptions,
    viewMode: DetailsViewMode,
    moveTo: DetailsMoveTo,
    groupTabs: Boolean,
    onEvent: (DownloadsEvent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIconButton(
            key = AllIconsKeys.General.Gear,
            tooltip = options.menuTooltip,
            onClick = { expanded = true },
        )
        if (expanded) {
            PopupMenu(
                onDismissRequest = { _ -> expanded = false; true },
                horizontalAlignment = Alignment.End,
            ) {
                selectableItem(selected = groupTabs, onClick = { onEvent(DownloadsEvent.ToggleGroupDetailsTabs(!groupTabs)) }) {
                    Text(options.groupTabs)
                }
                separator()
                submenu(submenu = {
                    viewModeItem(options.dockPinned, DetailsViewMode.DOCK_PINNED, viewMode, onEvent)
                    viewModeItem(options.dockUnpinned, DetailsViewMode.DOCK_UNPINNED, viewMode, onEvent)
                    viewModeItem(options.undock, DetailsViewMode.UNDOCK, viewMode, onEvent)
                    viewModeItem(options.float, DetailsViewMode.FLOAT, viewMode, onEvent)
                    viewModeItem(options.window, DetailsViewMode.WINDOW, viewMode, onEvent)
                }) { Text(options.viewMode) }
                submenu(submenu = {
                    moveToItem(options.leftTop, DetailsMoveTo.LEFT_TOP, moveTo, onEvent)
                    moveToItem(options.leftBottom, DetailsMoveTo.LEFT_BOTTOM, moveTo, onEvent)
                    moveToItem(options.bottomRight, DetailsMoveTo.BOTTOM_RIGHT, moveTo, onEvent)
                    moveToItem(options.bottomLeft, DetailsMoveTo.BOTTOM_LEFT, moveTo, onEvent)
                    moveToItem(options.rightBottom, DetailsMoveTo.RIGHT_BOTTOM, moveTo, onEvent)
                    moveToItem(options.rightTop, DetailsMoveTo.RIGHT_TOP, moveTo, onEvent)
                }) { Text(options.moveTo) }
                submenu(submenu = {
                    resizeItem(options.stretchLeft, DetailsResize.STRETCH_LEFT, onEvent)
                    resizeItem(options.stretchRight, DetailsResize.STRETCH_RIGHT, onEvent)
                    resizeItem(options.stretchTop, DetailsResize.STRETCH_TOP, onEvent)
                    resizeItem(options.stretchBottom, DetailsResize.STRETCH_BOTTOM, onEvent)
                    resizeItem(options.maximize, DetailsResize.MAXIMIZE, onEvent)
                }) { Text(options.resize) }
            }
        }
    }
}

private fun MenuScope.viewModeItem(
    label: String,
    mode: DetailsViewMode,
    current: DetailsViewMode,
    onEvent: (DownloadsEvent) -> Unit,
) {
    selectableItem(selected = current == mode, onClick = { onEvent(DownloadsEvent.SetDetailsViewMode(mode)) }) {
        Text(label)
    }
}

private fun MenuScope.moveToItem(
    label: String,
    position: DetailsMoveTo,
    current: DetailsMoveTo,
    onEvent: (DownloadsEvent) -> Unit,
) {
    selectableItem(selected = current == position, onClick = { onEvent(DownloadsEvent.SetDetailsMoveTo(position)) }) {
        Text(label)
    }
}

private fun MenuScope.resizeItem(
    label: String,
    preset: DetailsResize,
    onEvent: (DownloadsEvent) -> Unit,
) {
    selectableItem(selected = false, onClick = { onEvent(DownloadsEvent.ResizeDetails(preset)) }) {
        Text(label)
    }
}

@Composable
private fun DetailsBody(
    download: Download,
    generalLabel: String,
    filesLabel: String,
    groupTabs: Boolean,
    modifier: Modifier = Modifier,
) {
    val files = download.selectedFiles.orEmpty()
    val showFiles = files.size > 1
    var selectedTab by remember(download.id) { mutableIntStateOf(0) }
    val tabIndex = if (selectedTab == 1 && !showFiles) 0 else selectedTab

    Column(modifier = modifier.fillMaxWidth()) {
        val advisories = collectAdvisories(download)
        if (advisories.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    start = BlazeLayout.ContentPadding,
                    end = BlazeLayout.ContentPadding,
                    top = BlazeLayout.ContentPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                advisories.forEach { (text, tone) ->
                    AdvisoryCallout(text = text, tone = tone)
                }
            }
        }

        if (groupTabs) {
            // Grouped: General and Files run together in one continuous scroll, no tab strip.
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(BlazeLayout.ContentPaddingLarge),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GeneralTab(download)
                if (showFiles) {
                    Text(
                        text = filesLabel,
                        style = JewelTheme.typography.medium,
                        fontWeight = FontWeight.SemiBold,
                        color = JewelTheme.globalColors.text.normal,
                    )
                    FilesTab(files.map { it.path to it.size })
                }
            }
        } else {
            val tabs = buildList {
                add(
                    TabData.Default(
                        selected = tabIndex == 0,
                        closable = false,
                        onClick = { selectedTab = 0 },
                        content = { tabState -> SimpleTabContent(label = generalLabel, state = tabState) },
                    ),
                )
                if (showFiles) {
                    add(
                        TabData.Default(
                            selected = tabIndex == 1,
                            closable = false,
                            onClick = { selectedTab = 1 },
                            content = { tabState -> SimpleTabContent(label = filesLabel, state = tabState) },
                        ),
                    )
                }
            }
            TabStrip(
                tabs = tabs,
                style = JewelTheme.defaultTabStyle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = BlazeLayout.ContentPadding),
            )

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(BlazeLayout.ContentPaddingLarge),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (tabIndex) {
                    0 -> GeneralTab(download)
                    else -> FilesTab(files.map { it.path to it.size })
                }
            }
        }
    }
}

/** The advisories the row only gestures at, spelled out for the selected download. */
@Composable
private fun collectAdvisories(download: Download): List<Pair<String, AdvisoryTone>> {
    val strings = blazeStrings
    val advisories = strings.downloads.advisories
    val result = mutableListOf<Pair<String, AdvisoryTone>>()
    if (download.isWaitingForPeers()) result += advisories.waitingForPeers to AdvisoryTone.WARNING
    if (download.resumeSupport() == ResumeSupport.UNSUPPORTED) result += advisories.cannotResume to AdvisoryTone.WARNING
    if (download.pauseLosesProgress()) {
        result += strings.downloads.dialogs.pauseRisk.detail(download.percentDone()) to AdvisoryTone.WARNING
    }
    val fileMissing = (download.state == DownloadState.COMPLETED || download.state == DownloadState.SEEDING) &&
        runCatching { !File(download.savePath).exists() }.getOrDefault(false)
    if (fileMissing) result += advisories.fileMissing to AdvisoryTone.ERROR
    return result
}

@Composable
private fun GeneralTab(download: Download) {
    val strings = blazeStrings
    val details = strings.downloads.dialogs.details
    val advisories = strings.downloads.advisories
    val isActive = download.state == DownloadState.DOWNLOADING || download.state == DownloadState.SEEDING
    val scheduledInFuture = download.scheduledAt?.let { it > System.currentTimeMillis() } == true

    DetailRow(details.name) { Text(download.name) }
    DetailRow(details.status) { StatusBadge(download.state) }

    when (download.resumeSupport()) {
        ResumeSupport.SUPPORTED -> DetailRow(details.resumeSupport) { Text(advisories.resumeSupported) }
        ResumeSupport.UNSUPPORTED -> DetailRow(details.resumeSupport) {
            Text(advisories.cannotResume, color = BlazeColors.warning)
        }
        ResumeSupport.UNKNOWN -> Unit
    }

    val total = download.totalSize
    val sizeText = if (total != null) {
        strings.downloads.progress(formatSize(download.downloadedSize, strings), formatSize(total, strings)) +
            "  (${(download.progress * 100).toInt()}%)"
    } else {
        formatSize(download.downloadedSize, strings)
    }
    DetailRow(details.size) { Text(sizeText) }

    if (isActive && download.speed > 0) {
        DetailRow(details.speed) { Text(formatSpeed(download.speed, strings)) }
    }
    val eta = download.eta
    if (isActive && eta != null) {
        DetailRow(details.eta) { Text(formatDuration(eta)) }
    }
    if (download.peers > 0) {
        DetailRow(details.peers) { Text(strings.downloads.peers(download.peers)) }
    }
    download.selectedFiles?.let { files ->
        DetailRow(details.files) { Text(strings.downloads.filesCount(files.size)) }
    }

    DetailRow(details.source) {
        Text(
            download.url,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = JewelTheme.typography.consoleTextStyle,
        )
    }
    DetailRow(details.location) {
        Text(
            download.savePath,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = JewelTheme.typography.consoleTextStyle,
        )
    }

    DetailRow(details.added) { Text(formatDateTime(download.addedAt)) }
    if (scheduledInFuture) {
        DetailRow(details.scheduled) { Text(formatDateTime(download.scheduledAt)) }
    }

    download.error?.let { error ->
        DetailRow(details.error) {
            Text(text = error.toFriendlyMessage(strings), color = BlazeColors.error)
        }
    }
}

/** Per-file breakdown, shown as a tab only when the source genuinely holds several files. */
@Composable
private fun FilesTab(files: List<Pair<String, Long>>) {
    val strings = blazeStrings
    val secondary = JewelTheme.globalColors.text.info
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { (path, size) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = path.substringAfterLast('/'),
                    style = JewelTheme.typography.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = formatSize(size, strings),
                    style = JewelTheme.typography.small,
                    color = secondary,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: @Composable RowScope.() -> Unit) {
    val secondary = JewelTheme.globalColors.text.info
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = JewelTheme.typography.small,
            color = secondary,
            modifier = Modifier.width(DetailLabelWidth),
        )
        Row(modifier = Modifier.fillMaxWidth(), content = value)
    }
}

private val panelDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(panelDateTimeFormatter)
