package org.island.ui.toolwindow

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Descriptor representing an active tool window panel within the workspace.
 *
 * Everything about *where* the pane sits is data the owner persists; everything about *changing* it is
 * a callback back to that owner. The host never keeps a layout value of its own, so a pane restored
 * from settings and a pane the user just rearranged take the same path.
 */
data class ToolWindowSpec(
    val id: String,
    val title: String,
    val visible: Boolean = true,
    /**
     * Whether [content] paints its own [ToolWindowHeader]. A pane with its own header keeps it when
     * floating, so the host never adds a second one; a pane with no header of its own sets this to
     * false and the floating window supplies the title bar (and the drag surface).
     */
    val hasOwnHeader: Boolean = true,
    val viewMode: ToolWindowViewMode = ToolWindowViewMode.DOCK_PINNED,
    val moveTo: ToolWindowMoveTo = ToolWindowMoveTo.RIGHT_TOP,
    /**
     * The *content*'s share of the split that separates the central area from this pane, so the pane
     * itself holds `1 - splitFraction`. Ignored by the modes that cover the content rather than
     * reserving a share of it, where the same value decides how much of the frame the overlay covers.
     */
    val splitFraction: Float = 0.68f,
    /** This pane's share when two of them are stacked inside the same dock region. */
    val innerSplitFraction: Float = 0.50f,
    /**
     * Where a floating pane sits, in pixels inside the overlay layer. [Offset.Zero] is a request, not a
     * position: it means "this pane has no place yet", and the floating window answers with the corner
     * of the edge it is docked to. An owner clears the offset to detach a pane, or to move a free window
     * to the corner its "Move To" just picked, and the window writes the place it landed back.
     */
    val floatingOffset: Offset = Offset.Zero,
    val floatingSize: DpSize = DpSize(380.dp, 480.dp),
    val onMoveTo: (ToolWindowMoveTo) -> Unit = {},
    val onViewMode: (ToolWindowViewMode) -> Unit = {},
    val onSplitFractionChange: (Float) -> Unit = {},
    val onInnerSplitFractionChange: (Float) -> Unit = {},
    val onFloatingBoundsChange: (Offset, DpSize) -> Unit = { _, _ -> },
    val onResize: (ToolWindowResize) -> Unit = {},
    /**
     * Lowers the pane. The host calls it for the header's Hide button and for the auto-collapse of a
     * view mode that only lives while it holds attention ([ToolWindowViewMode.autoHidesWhenUnfocused]).
     */
    val onClose: () -> Unit = {},
    val content: @Composable () -> Unit,
)

/**
 * The live geometry of a floating tool window: where it sits in the overlay layer and how big it is.
 * Grouped into one value because the two are always reported and clamped together.
 */
data class FloatingBounds(
    val offset: Offset,
    val size: DpSize,
)
