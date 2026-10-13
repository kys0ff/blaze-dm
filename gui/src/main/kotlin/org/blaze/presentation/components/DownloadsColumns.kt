package org.blaze.presentation.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The download table's columns, left to right. [defaultWidth]/[minWidth] mirror the IDE's resizable
 * table: a column can be dragged narrower than its default but never below [minWidth], and the
 * header and every data row read the same width so cells stay aligned. [id] is the stable key used
 * to persist a user's chosen widths across launches. [endAligned] marks the numeric columns whose
 * header *and* cell right-align together (the IDE aligns a column header to its cell content).
 *
 * Blaze's own table model — it deliberately does not live in the reusable Islands layout tokens
 * ([org.island.theme.ui.IslandLayout]).
 */
enum class DownloadsColumn(
    val id: String,
    val defaultWidth: Dp,
    val minWidth: Dp,
    val endAligned: Boolean = false,
) {
    Name("name", 280.dp, 140.dp),
    Progress("progress", 150.dp, 90.dp),
    Size("size", 120.dp, 80.dp, endAligned = true),
    Speed("speed", 110.dp, 70.dp, endAligned = true),
    Eta("eta", 90.dp, 60.dp, endAligned = true),
    State("state", 170.dp, 110.dp),
}

/**
 * The columns the table actually renders, in table order, after the ones hidden from the header's
 * Table options menu. Both the header and every row read this, so a hidden column disappears from the
 * whole table at once and the remaining cells stay aligned. [DownloadsColumn.Name] can never be
 * hidden: it is how a row is identified and clicked.
 */
fun downloadsColumnsFor(hidden: Set<String>): List<DownloadsColumn> =
    DownloadsColumn.entries.filter { it == DownloadsColumn.Name || it.id !in hidden }
