package org.blaze.presentation.screens.downloads

import kotlin.math.min

/**
 * How the Details tool window is presented, mirroring an IDE tool window's "View Mode". Blaze keeps
 * everything in a single window, so the five modes map onto that reality rather than spawning real
 * OS windows. Two axes decide what a mode does:
 *
 * - does it *reserve* a share of the frame through the split ([isDocked]), or is it painted over the
 *   content ([overlaysContent])?
 * - does it stay put, or does it fold away the moment attention leaves it ([autoHidesWhenUnfocused])?
 *
 * [DOCK_PINNED] reserves its share and stays. [DOCK_UNPINNED] covers the content flush against the
 * edge it is docked to and folds away. [UNDOCK] and [FLOAT] are both free-moving windows over the
 * content; the first folds away, the second stays until it is closed. Both take the corner of the edge
 * they were docked to when they detach, and follow [DetailsMoveTo] while they are free. [WINDOW]
 * reserves the share the *pane* deserves (see [contentFractionForViewMode]).
 */
enum class DetailsViewMode {
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
 * Which edge or corner the Details pane docks to, mirroring an IDE tool window's "Move To". The
 * leading word is the dominant side (the pane's edge); the trailing word only picks the far corner
 * the pane hugs when it floats.
 */
enum class DetailsMoveTo {
    LEFT_TOP,
    LEFT_BOTTOM,
    RIGHT_TOP,
    RIGHT_BOTTOM,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    /** Left/Right split horizontally; the Bottom positions stack vertically under the list. */
    val isHorizontal: Boolean
        get() = this == LEFT_TOP || this == LEFT_BOTTOM || this == RIGHT_TOP || this == RIGHT_BOTTOM

    /** True when Details sits before the list (left); false when it follows (right/bottom). */
    val detailsFirst: Boolean get() = this == LEFT_TOP || this == LEFT_BOTTOM

    /** True when the pane is docked along the bottom rather than a vertical side. */
    val isBottom: Boolean get() = this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

/** The presets the Details pane's "Resize" submenu applies to the split. */
enum class DetailsResize { STRETCH_LEFT, STRETCH_RIGHT, STRETCH_TOP, STRETCH_BOTTOM, MAXIMIZE }

/**
 * The content's share of the split when a pane enters [DetailsViewMode.WINDOW]. The mode promises the
 * pane the larger half of the frame, so 0.35 hands the pane 65% of it.
 */
const val WINDOW_MODE_CONTENT_FRACTION = 0.35f

/**
 * The split fraction to keep when the pane takes a new [mode]. Entering [DetailsViewMode.WINDOW] moves
 * the split once, to honor the larger share the mode promises; every other mode leaves the split
 * exactly where the user dragged it, so switching away and back never loses a size.
 */
fun contentFractionForViewMode(mode: DetailsViewMode, currentFraction: Float): Float =
    if (mode == DetailsViewMode.WINDOW) min(currentFraction, WINDOW_MODE_CONTENT_FRACTION) else currentFraction

/** Rehydrates a persisted view-mode name, falling back to the default for a stale/hand-edited value. */
fun detailsViewModeFromName(name: String): DetailsViewMode =
    DetailsViewMode.entries.firstOrNull { it.name == name } ?: DetailsViewMode.DOCK_PINNED

/** Rehydrates a persisted move-to name, falling back to the current default (Details on the right). */
fun detailsMoveToFromName(name: String): DetailsMoveTo =
    DetailsMoveTo.entries.firstOrNull { it.name == name } ?: DetailsMoveTo.RIGHT_TOP
