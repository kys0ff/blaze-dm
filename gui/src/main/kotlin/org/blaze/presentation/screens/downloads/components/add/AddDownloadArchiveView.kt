package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.download.api.ArchiveEntryDto
import org.blaze.download.api.ArchiveInspectionDto
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.downloads.components.AdvisoryCallout
import org.blaze.presentation.screens.downloads.components.AdvisoryTone
import org.blaze.presentation.util.formatSize
import org.island.theme.ui.IslandTypography
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * The review step for a remote ZIP the user asked to browse before downloading (Phase 4).
 *
 * Three verdicts arrive from inspection and each is rendered honestly:
 *  - a browsable, range-served archive gets a checkbox tree so only the picked members are fetched;
 *  - a browsable archive the host will *not* serve ranges, and a full-download-only archive, both show
 *    the tree read-only and require an explicit acknowledgement before fetching the whole file — a
 *    large download is never triggered silently;
 *  - a non-archive is reported as an error and never reaches this view.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun AddDownloadArchiveView(state: AddDownloadState) {
    val strings = blazeStrings
    val d = strings.downloads.dialogs
    val a = d.archive
    val inspection = state.archiveInspection ?: return

    val entries: List<ArchiveEntryDto> = when (inspection) {
        is ArchiveInspectionDto.Browsable -> inspection.entries
        else -> emptyList()
    }
    val selective = inspection is ArchiveInspectionDto.Browsable && inspection.selectiveSupported

    // Every file that can actually be retrieved — directories and unsupported members are excluded from
    // both "select all" and the folder-prefix logic, so a tick never promises an entry that can't land.
    val selectableFiles = entries.filter { !it.isDirectory && it.extractable }.map { it.name }.toSet()

    fun filesUnder(prefix: String): Set<String> =
        selectableFiles.filter { it.startsWith(prefix) }.toSet()

    fun toggleFile(name: String) {
        state.selectedArchiveEntries =
            if (name in state.selectedArchiveEntries) state.selectedArchiveEntries - name
            else state.selectedArchiveEntries + name
    }

    fun toggleDir(dir: ArchiveEntryDto) {
        val children = filesUnder(dir.name)
        if (children.isEmpty()) return
        val allOn = state.selectedArchiveEntries.containsAll(children)
        state.selectedArchiveEntries =
            if (allOn) state.selectedArchiveEntries - children else state.selectedArchiveEntries + children
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = a.entriesTitle, style = IslandTypography.emphasizedHeader)

        if (!selective) {
            // Ranges are unavailable: say so plainly and gate the whole-archive fetch behind a choice.
            AdvisoryCallout(a.selectiveUnsupported, AdvisoryTone.WARNING)
        } else {
            Text(a.hint, color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
        }

        if (entries.isNotEmpty()) {
            val listState = rememberLazyListState()
            VerticallyScrollableContainer(
                scrollState = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.3f))
                    .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp)),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    if (selective) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = d.selectAll,
                                    color = JewelTheme.globalColors.text.info,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { state.selectedArchiveEntries = selectableFiles },
                                )
                                Text(
                                    text = d.deselectAll,
                                    color = JewelTheme.globalColors.text.info,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { state.selectedArchiveEntries = emptySet() },
                                )
                            }
                        }
                    }
                    items(entries) { entry ->
                        val children = filesUnder(entry.name)
                        val checked = if (entry.isDirectory) {
                            children.isNotEmpty() && state.selectedArchiveEntries.containsAll(children)
                        } else {
                            entry.name in state.selectedArchiveEntries
                        }
                        ArchiveEntryRow(
                            entry = entry,
                            selective = selective,
                            checked = checked,
                            onToggle = {
                                if (selective) {
                                    if (entry.isDirectory) toggleDir(entry) else toggleFile(entry.name)
                                }
                            },
                            strings = strings,
                        )
                    }
                }
            }

            if (selective) {
                Text(
                    text = a.estimatedSelected(formatSize(state.archiveSelectedBytes, strings)),
                    color = JewelTheme.globalColors.text.info,
                    fontSize = 12.sp,
                )
            }
        }

        // The only way to fetch a whole archive is an explicit, checkbox-gated choice, so an oversized
        // download is never the silent default behind a "Download" press.
        if (!selective) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = state.archiveDownloadAllAcknowledged,
                    onCheckedChange = { state.archiveDownloadAllAcknowledged = it },
                )
                Text(a.downloadWholeArchive)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.5f))
                .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.saveTo, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(90.dp))
                Text(
                    state.destination.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = JewelTheme.globalColors.text.info
                )
            }
        }

        OutlinedButton(onClick = { state.step = 1 }) {
            Text(d.backToEdit)
        }
    }
}

@Composable
private fun ArchiveEntryRow(
    entry: ArchiveEntryDto,
    selective: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    strings: org.blaze.i18n.BlazeStrings,
) {
    val a = strings.downloads.dialogs.archive
    // Depth-indent the flat central-directory listing so nested members read as a hierarchy.
    val depth = entry.name.trimEnd('/').count { it == '/' }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selective) { onToggle() }
            .padding(start = (8 + depth * 12).dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selective) {
            Checkbox(
                checked = checked,
                enabled = entry.isDirectory || entry.extractable,
                onCheckedChange = { onToggle() },
            )
        }
        Text(
            text = entry.name.trimEnd('/').substringAfterLast('/').ifBlank { entry.name },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (entry.isDirectory) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info,
        )
        if (!entry.extractable && !entry.isDirectory) {
            Text(a.notExtractable, color = JewelTheme.globalColors.text.disabled, fontSize = 11.sp)
        } else if (!entry.isDirectory) {
            Text(formatSize(entry.uncompressedSize, strings), fontSize = 12.sp)
        }
    }
}
