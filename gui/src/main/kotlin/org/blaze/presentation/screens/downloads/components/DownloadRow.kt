package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.download.api.DownloadPriority
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.BlazeMenu
import org.blaze.presentation.components.DownloadsColumn
import org.blaze.presentation.components.LocalWindowActive
import org.blaze.presentation.components.rememberMenuAnchorAt
import org.blaze.presentation.screens.downloads.DownloadCapabilities
import org.blaze.presentation.screens.downloads.isTorrentSource
import org.blaze.presentation.screens.downloads.isUnresumableInProgress
import org.blaze.presentation.screens.downloads.isWaitingForPeers
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.theme.BlazeTypography
import org.blaze.presentation.util.formatDuration
import org.blaze.presentation.util.formatSize
import org.blaze.presentation.util.formatSpeed
import org.blaze.presentation.util.toFriendlyMessage
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.HorizontalProgressBarColors
import org.jetbrains.jewel.ui.component.styling.HorizontalProgressBarStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.horizontalProgressBarStyle
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import org.jetbrains.jewel.ui.typography
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private val RowShape = RoundedCornerShape(4.dp)
internal val CellStartPadding = 8.dp
internal val CellEndPadding = 10.dp

/**
 * Trailing gutter sized to hold the header's options (gear) button. Now the leading selection
 * checkbox is gone, every row reserves this same width so its weighted columns line up with the
 * header exactly.
 */
val OptionsGutterWidth = 28.dp

/** OpenType feature that gives fixed-width digits, so numbers line up down a numeric column. */
internal const val TabularFigures = "tnum"

/**
 * Resolves a column's share of the table width. Every cell in the header and in a row reads this,
 * so columns stay perfectly aligned; using the dp value as a weight lets the row always fill the
 * available width (the IDE's stretchy table) while a drag re-weights just the resized column.
 */
internal fun DownloadsColumn.weightFor(widths: Map<String, Float>): Float =
    widths[id] ?: defaultWidth.value

/**
 * Whether a finished download's artifact is still on disk. A completed transfer's entry rarely
 * changes, so without a periodic re-check a file deleted by the OS would leave the row looking
 * healthy until the next unrelated list update. Only visible rows are composed.
 */
private const val ARTIFACT_CHECK_INTERVAL_MS = 3_000L

private fun downloadArtifactExists(savePath: String): Boolean =
    runCatching { File(savePath).exists() }.getOrDefault(false)

/**
 * The row's leading icon. Active transport states own their glyph (uploading seeds, failures),
 * while a resting row is identified by *what it is*: a torrent reads as a folder, a finished
 * single file reads as its file type, and an in-flight web link keeps the download glyph.
 */
private fun Download.leadingIconKey(): IconKey = when {
    state == DownloadState.SEEDING -> AllIconsKeys.Actions.Upload
    state == DownloadState.FAILED -> AllIconsKeys.General.Error
    isTorrentSource() -> AllIconsKeys.Nodes.Folder
    state == DownloadState.COMPLETED -> fileTypeIcon(name)
    else -> AllIconsKeys.Actions.Download
}

private val ARCHIVE_EXTENSIONS =
    setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "zst")

private fun fileTypeIcon(fileName: String): IconKey {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return if (extension in ARCHIVE_EXTENSIONS) AllIconsKeys.FileTypes.Archive else AllIconsKeys.FileTypes.Any_type
}

/**
 * Reports a secondary-button (right-button) click in window-root coordinates, which is the point a
 * context menu opens at. It fires on the release, like the platform's own menus, so pressing, dragging
 * away and letting go leaves nothing behind.
 */
private fun Modifier.onSecondaryTap(onTap: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitPointerEvent(PointerEventPass.Main)
        val pressed = down.changes.firstOrNull { it.pressed && !it.previousPressed }
        if (pressed == null || !down.buttons.isSecondaryPressed) return@awaitEachGesture
        val position = pressed.position
        // Hold until every pointer is up again; the gesture is the whole press, not its first frame.
        var released = false
        while (!released) {
            released = awaitPointerEvent(PointerEventPass.Main).changes.none { it.pressed }
        }
        onTap(position)
    }
}

/**
 * Turns a row into a selectable surface without a checkbox: a primary-button tap selects, honoring
 * the keyboard modifiers the multi-select rules need — Ctrl/Cmd toggles the row in/out (additive),
 * Shift extends the span from the anchor (range) — and a double-tap opens a finished file. A
 * non-primary press is left for the row's own context menu.
 */
private fun Modifier.rowTapHandler(
    onSelect: (additive: Boolean, range: Boolean) -> Unit,
    onOpen: () -> Unit,
): Modifier = pointerInput(Unit) {
    var lastTap = 0L
    awaitEachGesture {
        // The button and the held Ctrl/Cmd/Shift live on the *PointerEvent*, not on the per-pointer
        // change, so read the down event itself. A non-primary press is left for the row's own context
        // menu to handle.
        val downEvent = awaitPointerEvent()
        if (downEvent.changes.none { it.pressed && !it.previousPressed }) return@awaitEachGesture
        if (!downEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        val modifiers = downEvent.keyboardModifiers
        val additive = modifiers.isCtrlPressed || modifiers.isMetaPressed
        val range = modifiers.isShiftPressed
        // Wait for the release that completes the tap; a consumed change means something else claimed it.
        var released = false
        var cancelled = false
        while (!released && !cancelled) {
            val event = awaitPointerEvent()
            released = event.changes.any { !it.pressed && it.previousPressed }
            cancelled = event.changes.any { it.isConsumed }
        }
        if (cancelled) return@awaitEachGesture
        val now = System.currentTimeMillis()
        if (now - lastTap < viewConfiguration.doubleTapTimeoutMillis) {
            lastTap = 0L
            onOpen()
        } else {
            lastTap = now
            onSelect(additive, range)
        }
    }
}

/** A themed tooltip bubble (Compose foundation's `TooltipArea` is not Material), styled from tokens. */
@Composable
private fun TableCellTooltip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = text, style = JewelTheme.typography.small)
    }
}

/**
 * One download rendered as an IDE table row: a fixed [BlazeLayout.RowHeight] tall, single-line row
 * split into the six weighted columns so it aligns exactly with [DownloadsTableHeader]. Everything
 * the wide two-line row used to carry is preserved but relocated: the per-file breakdown and the
 * lifecycle advisories now surface in the Details pane, and the destructive-pause warning lives on
 * the Pause affordance's tooltip - see the row element -> location mapping.
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadRow(
    download: Download,
    actions: DownloadRowActions,
    columnWidths: Map<String, Float>,
    /** The columns this row renders, in table order — the same list the header draws. */
    columns: List<DownloadsColumn>,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onSelect: (additive: Boolean, range: Boolean) -> Unit = { _, _ -> },
    hideResume: Boolean = false,
    capabilities: DownloadCapabilities = DownloadCapabilities(),
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val listColors = JewelTheme.simpleListItemStyle.colors
    // The IDE paints the blue selection only while its window is focused; a blurred frame falls back
    // to the gray inactive selection, exactly like a focused-then-unfocused tool window.
    val windowActive = LocalWindowActive.current
    val selectedBg =
        if (windowActive) listColors.backgroundSelectedActive else listColors.backgroundSelected
    val selectedFg =
        if (windowActive) listColors.contentSelectedActive else listColors.contentSelected
    val strings = blazeStrings

    val isFailed = download.state == DownloadState.FAILED
    val isCompleted =
        download.state == DownloadState.COMPLETED || download.state == DownloadState.SEEDING

    var fileMissing by remember(download.id, download.state, download.savePath) {
        mutableStateOf(isCompleted && !downloadArtifactExists(download.savePath))
    }
    if (isCompleted) {
        LaunchedEffect(download.id, download.savePath) {
            while (true) {
                fileMissing = !downloadArtifactExists(download.savePath)
                delay(ARTIFACT_CHECK_INTERVAL_MS.milliseconds)
            }
        }
    }

    val background = when {
        isSelected -> selectedBg
        hovered -> BlazeColors.hover
        else -> Color.Transparent
    }
    val disabledText = JewelTheme.globalColors.text.disabled
    val primary = when {
        isSelected -> selectedFg
        fileMissing -> disabledText
        else -> Color.Unspecified
    }
    val secondary = when {
        isSelected -> selectedFg
        fileMissing -> disabledText
        else -> JewelTheme.globalColors.text.info
    }

    // ── Conditions the State cell surfaces + the tooltip explains ───────────────
    val waitingForPeers = download.isWaitingForPeers()
    val cannotResume = !isCompleted && !isFailed && download.isUnresumableInProgress()
    val stateTooltip = buildString {
        if (waitingForPeers) append(strings.downloads.advisories.waitingForPeers)
        if (cannotResume) {
            if (isNotEmpty()) append("\n")
            append(strings.downloads.advisories.cannotResume)
        }
        if (fileMissing) {
            if (isNotEmpty()) append("\n")
            append(strings.downloads.advisories.fileMissing)
        }
    }

    // The row's own context menu. This used to go through Compose's `ContextMenuArea`, whose Jewel
    // representation hangs the mouse inside any submenu (the reason Blaze draws its own menus — see
    // BlazeMenu); taking the right-click here is also what hands the menu the cursor point it opens at.
    var menuPosition by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSecondaryTap { menuPosition = it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BlazeLayout.RowHeight)
                .clip(RowShape)
                .background(background)
                .hoverable(interaction)
                .rowTapHandler(
                    onSelect = onSelect,
                    onOpen = { if (isCompleted && !fileMissing) actions.openFile() },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Columns, in order, weighted to match the header ──
            columns.forEach { column ->
                val cellModifier = Modifier
                    .weight(column.weightFor(columnWidths))
                    .fillMaxHeight()
                    .padding(start = CellStartPadding, end = CellEndPadding)
                when (column) {
                    DownloadsColumn.Name -> Box(
                        cellModifier,
                        contentAlignment = Alignment.CenterStart
                    ) {
                        NameCell(
                            download,
                            primary,
                            secondary,
                            iconTint = if (fileMissing) disabledText else Color.Unspecified,
                            strings = strings
                        )
                    }

                    DownloadsColumn.Progress -> Box(
                        cellModifier,
                        contentAlignment = Alignment.CenterStart
                    ) {
                        ProgressCell(download, secondary, strings)
                    }

                    DownloadsColumn.Size -> Box(
                        cellModifier,
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = sizeText(download, strings),
                            style = BlazeTypography.rowCell,
                            // The same secondary role as speed and ETA: every numeric readout in the row
                            // sits one step behind the name. Painting only the size in the row's primary
                            // color makes it read as a heavier face even though its resolved
                            // FontWeight is the same 400 the font audit prints.
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    DownloadsColumn.Speed -> Box(
                        cellModifier,
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = speedText(download, strings),
                            style = BlazeTypography.rowCell,
                            color = secondary,
                            maxLines = 1,
                        )
                    }

                    DownloadsColumn.Eta -> Box(
                        cellModifier,
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = etaText(download, strings),
                            style = BlazeTypography.rowCell,
                            color = secondary,
                            maxLines = 1,
                        )
                    }

                    DownloadsColumn.State -> {
                        val stateContent: @Composable () -> Unit = {
                            Box(cellModifier, contentAlignment = Alignment.CenterStart) {
                                StateCell(
                                    download,
                                    waitingForPeers,
                                    cannotResume,
                                    fileMissing,
                                    primary,
                                    secondary,
                                    strings
                                )
                            }
                        }
                        if (stateTooltip.isNotEmpty()) {
                            TableCellTooltipContent(text = stateTooltip, content = stateContent)
                        } else {
                            stateContent()
                        }
                    }
                }
            }

            // ── Trailing gutter that mirrors the header's options (gear) slot ──
            Box(modifier = Modifier.width(OptionsGutterWidth).fillMaxHeight())
        }

        val point = menuPosition
        if (point != null) {
            BlazeMenu(
                entries = buildDownloadContextMenu(
                    download,
                    capabilities,
                    strings,
                    actions,
                    hideResume,
                    fileMissing,
                ),
                anchor = rememberMenuAnchorAt(point),
                onDismissRequest = { menuPosition = null },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableCellTooltipContent(text: String, content: @Composable () -> Unit) {
    TooltipArea(tooltip = { TableCellTooltip(text) }, content = content)
}

@Composable
private fun NameCell(
    download: Download,
    primary: Color,
    secondary: Color,
    iconTint: Color,
    strings: BlazeStrings,
) {
    val files = download.selectedFiles.orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            key = download.leadingIconKey(),
            contentDescription = download.state.label(strings),
            tint = iconTint,
            modifier = Modifier.size(BlazeLayout.RowLeadingIconSize),
        )
        Text(
            text = download.name,
            color = primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (files.isNotEmpty()) {
            Text(
                text = "(${strings.downloads.filesCount(files.size)})",
                style = JewelTheme.typography.small,
                color = secondary,
                maxLines = 1,
            )
        }
        if (download.priority != DownloadPriority.NORMAL) {
            PriorityChip(download.priority)
        }
    }
}

@Composable
private fun ProgressCell(
    download: Download,
    secondary: Color,
    strings: BlazeStrings
) { // TODO: make use of strings or remove it
    val isIndeterminate = download.state == DownloadState.DOWNLOADING &&
            (download.totalSize == null || (download.downloadedSize == 0L && download.speed == 0L))
    val progress = download.progress.coerceIn(0f, 1f)

    // A finished transfer is done transferring, whether it is COMPLETED or still SEEDING to peers: its
    // full-width bar is noise, because the row already says "100%" and names the state, and a saturated
    // strip across every done row is what makes a list of finished work look busy. A finished row keeps
    // only the readout, in the secondary color, in the space where the bar was.
    //
    // While a transfer is *not* done, the fill is the row's own state color (the same
    // [indicatorColor] ramp the badge uses), so the column reads as status rather than as decoration:
    // paused amber, failed red, downloading/queued the accent.
    val isCompleted = download.state == DownloadState.COMPLETED || download.state == DownloadState.SEEDING
    val stateFill = download.state.indicatorColor()
    val baseStyle = JewelTheme.horizontalProgressBarStyle
    val barStyle = remember(baseStyle, stateFill) { baseStyle.copyColors(progress = stateFill) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            // A finished row keeps only the readout, and it keeps it at the column's start: the header
            // label "Progress" is start-aligned too, so pushing "100%" to the far end of an empty
            // column would leave the two visually unrelated.
            isCompleted -> {}
            isIndeterminate -> IndeterminateHorizontalProgressBar(
                modifier = Modifier.weight(1f).height(4.dp)
            )
            else -> HorizontalProgressBar(
                progress = progress,
                modifier = Modifier.weight(1f).height(4.dp),
                style = barStyle,
            )
        }
        Text(
            text = if (isIndeterminate) "\u2014" else "${(progress * 100).toInt()}%",
            style = BlazeTypography.rowCell,
            color = secondary,
            maxLines = 1,
        )
    }
}

/**
 * The theme's progress-bar style with only its [progress] color swapped: Jewel's style has no
 * `copy`, so the colors are rebuilt from the live one, keeping the track and indeterminate ramp
 * (and therefore the theme) intact.
 */
private fun HorizontalProgressBarStyle.copyColors(progress: Color) = HorizontalProgressBarStyle(
    colors = HorizontalProgressBarColors(
        track = colors.track,
        progress = progress,
        indeterminateBase = colors.indeterminateBase,
        indeterminateHighlight = colors.indeterminateHighlight,
    ),
    metrics = metrics,
    indeterminateCycleDuration = indeterminateCycleDuration,
)

@Composable
private fun StateCell(
    download: Download,
    waitingForPeers: Boolean,
    cannotResume: Boolean,
    fileMissing: Boolean,
    primary: Color,
    secondary: Color, // TODO: make use of secondary
    strings: BlazeStrings,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (waitingForPeers) {
            // "Waiting for peers" reads as the state itself; full detail lives in the cell tooltip.
            Text(
                text = strings.downloads.advisories.waitingForPeers,
                style = JewelTheme.typography.small,
                color = if (primary != Color.Unspecified) primary else BlazeColors.warning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            StatusBadge(download.state, dimmed = fileMissing)
        }
        if (download.state == DownloadState.FAILED) {
            download.error?.let { error ->
                Text(
                    text = error.toFriendlyMessage(strings),
                    style = JewelTheme.typography.small,
                    color = if (primary != Color.Unspecified) primary else BlazeColors.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (cannotResume) {
            Icon(
                key = AllIconsKeys.General.Warning,
                contentDescription = strings.downloads.advisories.cannotResume,
                tint = BlazeColors.warning,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun sizeText(download: Download, strings: BlazeStrings): String {
    val total = download.totalSize
    return if (total != null) {
        strings.downloads.progress(
            formatSize(download.downloadedSize, strings),
            formatSize(total, strings)
        )
    } else {
        formatSize(download.downloadedSize, strings)
    }
}

private fun speedText(download: Download, strings: BlazeStrings): String {
    val active =
        download.state == DownloadState.DOWNLOADING || download.state == DownloadState.SEEDING
    return if (active && download.speed > 0) formatSpeed(download.speed, strings) else "\u2014"
}

private fun etaText(
    download: Download,
    strings: BlazeStrings
): String { // TODO: make use of strings or remove it
    val active =
        download.state == DownloadState.DOWNLOADING || download.state == DownloadState.SEEDING
    val eta = download.eta
    return if (active && eta != null && (download.downloadedSize > 0L || download.speed > 0L)) {
        formatDuration(eta)
    } else {
        "\u2014"
    }
}
