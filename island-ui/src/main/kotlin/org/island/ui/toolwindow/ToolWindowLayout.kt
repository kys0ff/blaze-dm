package org.island.ui.toolwindow

import kotlin.math.min

/**
 * How a dockable tool window is presented, mirroring an IDE tool window's "View Mode". A shell that
 * keeps everything in a single window maps the five modes onto that reality rather than spawning real
 * OS windows. Two axes decide what a mode does:
 *
 * - does it *reserve* a share of the frame through the split ([isDocked]), or is it painted over the
 *   content ([overlaysContent])?
 * - does it stay put, or does it fold away the moment attention leaves it ([autoHidesWhenUnfocused])?
 *
 * [DOCK_PINNED] reserves its share and stays. [DOCK_UNPINNED] covers the content flush against the
 * edge it is docked to and folds away. [UNDOCK] and [FLOAT] are both free-moving windows over the
 * content; the first folds away, the second stays until it is closed. Both take the corner of the edge
 * they were docked to when they detach, and follow [ToolWindowMoveTo] while they are free. [WINDOW]
 * reserves the share the *pane* deserves (see [contentFractionForViewMode]).
 */
enum class ToolWindowViewMode {
    DOCK_PINNED,
    DOCK_UNPINNED,
    UNDOCK,
    FLOAT,
    WINDOW,
    ;

    /** True when the pane holds a permanent share of the frame through the split. */
    val isDocked: Boolean get() = this == DOCK_PINNED || this == WINDOW

    /** True when the pane is drawn over the content instead of reserving a share of it. */
    val overlaysContent: Boolean get() = !isDocked

    /** True for the overlays the user can drag and resize: [FLOAT] and [UNDOCK]. */
    val isFloatingWindow: Boolean get() = this == FLOAT || this == UNDOCK

    /**
     * True when the pane folds away as soon as attention leaves it — a press outside it, or the frame
     * losing focus. Those are the two ways an IDE drops an unpinned tool window, and they are exactly
     * what a pinned pane and a floating window refuse to do.
     */
    val autoHidesWhenUnfocused: Boolean get() = this == DOCK_UNPINNED || this == UNDOCK

    /** An unpinned pane folds away when there is nothing selected to show, unlike a pinned one. */
    val autoHidesWhenEmpty: Boolean get() = this == DOCK_UNPINNED
}

/**
 * Which edge or corner a tool window docks to, mirroring an IDE tool window's "Move To". The leading
 * word is the dominant side (the pane's edge); the trailing word only picks the far corner the pane
 * hugs when it floats.
 */
enum class ToolWindowMoveTo {
    LEFT_TOP,
    LEFT_BOTTOM,
    RIGHT_TOP,
    RIGHT_BOTTOM,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    /** Left/Right split horizontally; the Bottom positions stack vertically under the content. */
    val isHorizontal: Boolean // TODO: make use of this val
        get() = this == LEFT_TOP || this == LEFT_BOTTOM || this == RIGHT_TOP || this == RIGHT_BOTTOM

    /** True when the pane sits before the content (left); false when it follows (right/bottom). */
    val detailsFirst: Boolean get() = this == LEFT_TOP || this == LEFT_BOTTOM // TODO: make use of this val

    /** True when the pane is docked along the bottom rather than a vertical side. */
    val isBottom: Boolean get() = this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

/** The presets a tool window's "Resize" submenu applies to the split. */
enum class ToolWindowResize { STRETCH_LEFT, STRETCH_RIGHT, STRETCH_TOP, STRETCH_BOTTOM, MAXIMIZE }

/**
 * The content's share of the split when a pane enters [ToolWindowViewMode.WINDOW]. The mode promises the
 * pane the larger half of the frame, so 0.35 hands the pane 65% of it.
 */
const val WINDOW_MODE_CONTENT_FRACTION = 0.35f

/** The narrowest share either side of a split may hold. */
const val MIN_SPLIT_FRACTION = 0.15f

/** The widest share either side of a split may hold. */
const val MAX_SPLIT_FRACTION = 0.85f

/**
 * The split fraction to keep when the pane takes a new [mode]. Entering [ToolWindowViewMode.WINDOW] moves
 * the split once, to honor the larger share the mode promises; every other mode leaves the split
 * exactly where the user dragged it, so switching away and back never loses a size.
 */
fun contentFractionForViewMode(mode: ToolWindowViewMode, currentFraction: Float): Float =
    if (mode == ToolWindowViewMode.WINDOW) min(currentFraction, WINDOW_MODE_CONTENT_FRACTION) else currentFraction

/** Rehydrates a persisted view-mode name, falling back to the default for a stale/hand-edited value. */
fun toolWindowViewModeFromName(name: String): ToolWindowViewMode =
    ToolWindowViewMode.entries.firstOrNull { it.name == name } ?: ToolWindowViewMode.DOCK_PINNED

/** Rehydrates a persisted move-to name, falling back to the current default (pane on the right). */
fun toolWindowMoveToFromName(name: String): ToolWindowMoveTo =
    ToolWindowMoveTo.entries.firstOrNull { it.name == name } ?: ToolWindowMoveTo.RIGHT_TOP

/**
 * Everything about *where* a dockable tool window sits, in the same shape for every pane. The values
 * are the persisted layout; the [ToolWindowLayoutAction]s below are the only way they change, and
 * [reduceToolWindowLayout] is the single place those rules live so several panes never drift apart.
 * Defaults here are placeholders — a pane's store seeds the real ones.
 */
data class ToolWindowLayout(
    val visible: Boolean = false,
    val viewMode: ToolWindowViewMode = ToolWindowViewMode.DOCK_PINNED,
    val moveTo: ToolWindowMoveTo = ToolWindowMoveTo.RIGHT_TOP,
    /** Fraction of the split given to the *content* (everything but the pane), so the pane holds `1 - it`. */
    val splitFraction: Float = 0.68f,
    /** This pane's share when two of them are stacked inside the same dock region. */
    val innerSplitFraction: Float = 0.50f,
    val floatingOffsetX: Float = 0f,
    val floatingOffsetY: Float = 0f,
    val floatingWidthDp: Float = 380f,
    val floatingHeightDp: Float = 480f,
)

/**
 * A request to change one aspect of a pane's layout. Pure data — [reduceToolWindowLayout] decides the
 * new [ToolWindowLayout] — so the mutation rules are testable without a coroutine scope or a model.
 * "Resize" presets are deliberately absent: their meaning is pane-specific and each pane composes these
 * generic actions to express it, so no fake one-size rule is introduced.
 */
sealed interface ToolWindowLayoutAction {
    data class SetVisible(val visible: Boolean) : ToolWindowLayoutAction
    data object ToggleVisible : ToolWindowLayoutAction
    data class SetViewMode(val mode: ToolWindowViewMode) : ToolWindowLayoutAction
    data class SetMoveTo(val position: ToolWindowMoveTo) : ToolWindowLayoutAction
    data class SetSplitFraction(val fraction: Float) : ToolWindowLayoutAction
    data class SetInnerSplitFraction(val fraction: Float) : ToolWindowLayoutAction
    data class SetFloatingBounds(
        val x: Float,
        val y: Float,
        val widthDp: Float,
        val heightDp: Float,
    ) : ToolWindowLayoutAction
}

/**
 * Folds a [action] into [layout], applying the shared dockable-window semantics every pane relies on:
 * entering [ToolWindowViewMode.WINDOW] re-negotiates the split once; detaching a docked pane into a
 * floating mode clears its placement so the overlay layer hands it the corner of the edge it leaves; a
 * "Move To" while free re-anchors it to the new corner; and a dragged split is clamped to the usable
 * band. This is the single source of those rules.
 */
fun reduceToolWindowLayout(layout: ToolWindowLayout, action: ToolWindowLayoutAction): ToolWindowLayout =
    when (action) {
        is ToolWindowLayoutAction.SetVisible -> layout.copy(visible = action.visible)

        ToolWindowLayoutAction.ToggleVisible -> layout.copy(visible = !layout.visible)

        is ToolWindowLayoutAction.SetViewMode -> {
            val detaching = action.mode.isFloatingWindow && !layout.viewMode.isFloatingWindow
            layout.copy(
                viewMode = action.mode,
                splitFraction = contentFractionForViewMode(action.mode, layout.splitFraction),
                floatingOffsetX = if (detaching) 0f else layout.floatingOffsetX,
                floatingOffsetY = if (detaching) 0f else layout.floatingOffsetY,
            )
        }

        is ToolWindowLayoutAction.SetMoveTo ->
            // A docked pane only changes which split it sits in. A free window *is* its edge, so the
            // same choice moves it to that corner by clearing the placement for the layer to recompute.
            if (layout.viewMode.isFloatingWindow) {
                layout.copy(moveTo = action.position, floatingOffsetX = 0f, floatingOffsetY = 0f)
            } else {
                layout.copy(moveTo = action.position)
            }

        is ToolWindowLayoutAction.SetSplitFraction ->
            layout.copy(splitFraction = action.fraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION))

        is ToolWindowLayoutAction.SetInnerSplitFraction ->
            layout.copy(innerSplitFraction = action.fraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION))

        is ToolWindowLayoutAction.SetFloatingBounds ->
            layout.copy(
                floatingOffsetX = action.x,
                floatingOffsetY = action.y,
                floatingWidthDp = action.widthDp,
                floatingHeightDp = action.heightDp,
            )
    }
