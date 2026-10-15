package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.downloads.*
import org.blaze.presentation.util.formatDuration
import org.blaze.presentation.util.formatSize
import org.blaze.presentation.util.formatSpeed
import org.blaze.presentation.util.toFriendlyMessage
import org.island.theme.ui.IslandLayout
import org.island.theme.ui.IslandSemanticColors
import org.island.theme.ui.IslandTypography
import org.island.ui.toolbar.IslandToolbarIconButton
import org.island.ui.toolwindow.*
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
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
    viewMode: ToolWindowViewMode = ToolWindowViewMode.DOCK_PINNED,
    moveTo: ToolWindowMoveTo = ToolWindowMoveTo.RIGHT_TOP,
    groupTabs: Boolean = false,
    onViewMode: (ToolWindowViewMode) -> Unit = {},
    onMoveTo: (ToolWindowMoveTo) -> Unit = {},
    onResize: (ToolWindowResize) -> Unit = {},
    onToggleGroupTabs: (Boolean) -> Unit = {},
) {
    val strings = blazeStrings
    val pane = strings.downloads.detailsPane
    Column(modifier = modifier.fillMaxSize()) {
        ToolWindowHeader(
            title = strings.downloads.dialogs.details.title,
            actions = {
                ToolWindowOptionsButton(
                    viewMode = viewMode,
                    moveTo = moveTo,
                    groupTabs = groupTabs,
                    onToggleGroupTabs = onToggleGroupTabs,
                    onViewMode = onViewMode,
                    onMoveTo = onMoveTo,
                    onResize = onResize,
                )
                // A tool window is *hidden*, not closed: the IDE draws the horizontal-line glyph for that
                // and keeps the X for closing an editor. `General.Close` here read as "destroy the pane".
                IslandToolbarIconButton(
                    key = AllIconsKeys.General.HideToolWindow,
                    tooltip = pane.hide,
                    onClick = onHide,
                )
            },
        )
        if (download == null) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(IslandLayout.ContentPaddingLarge),
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
 * The Details pane's tool-window "more" button lives in the shared
 * [org.island.ui.toolwindow.ToolWindowOptionsButton]; the Server Logs window mounts the same
 * menu, so it is no longer duplicated here.
 */
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
                    start = IslandLayout.ContentPadding,
                    end = IslandLayout.ContentPadding,
                    top = IslandLayout.ContentPadding,
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
            // The Jewel container owns the scroll so the pane gets the overlay scrollbar like every
            // other scroll area in the chrome.
            VerticallyScrollableContainer(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .padding(IslandLayout.ContentPaddingLarge),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GeneralTab(download)
                    if (showFiles) {
                        Text(
                            text = filesLabel,
                            // The grouped view's one section heading, i.e. a group header - the exempt
                            // family. Routed through the shared style so its weight is audited.
                            style = IslandTypography.emphasizedHeader,
                            color = JewelTheme.globalColors.text.normal,
                        )
                        FilesTab(files.map { it.path to it.size })
                    }
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = IslandLayout.ContentPadding),
            )

            VerticallyScrollableContainer(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .padding(IslandLayout.ContentPaddingLarge),
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
            Text(advisories.cannotResume, color = IslandSemanticColors.warning)
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
            Text(text = error.toFriendlyMessage(strings), color = IslandSemanticColors.error)
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
