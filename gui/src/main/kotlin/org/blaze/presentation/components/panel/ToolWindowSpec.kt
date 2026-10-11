package org.blaze.presentation.components.panel

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.blaze.presentation.screens.downloads.DetailsResize
import org.blaze.presentation.screens.downloads.DetailsViewMode

/**
 * Descriptor representing an active tool window panel within the workspace.
 */
data class ToolWindowSpec(
    val id: String,
    val title: String,
    val visible: Boolean = true,
    val viewMode: DetailsViewMode = DetailsViewMode.DOCK_PINNED,
    val moveTo: DetailsMoveTo = DetailsMoveTo.RIGHT_TOP,
    val splitFraction: Float = 0.68f,
    val innerSplitFraction: Float = 0.50f,
    val floatingOffset: Offset = Offset.Zero,
    val floatingSize: DpSize = DpSize(380.dp, 480.dp),
    val onMoveTo: (DetailsMoveTo) -> Unit = {},
    val onViewMode: (DetailsViewMode) -> Unit = {},
    val onSplitFractionChange: (Float) -> Unit = {},
    val onInnerSplitFractionChange: (Float) -> Unit = {},
    val onFloatingBoundsChange: (Offset, DpSize) -> Unit = { _, _ -> },
    val onResize: (DetailsResize) -> Unit = {},
    val onClose: () -> Unit = {},
    val content: @Composable () -> Unit,
)
