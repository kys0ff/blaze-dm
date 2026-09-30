package org.blaze.presentation.screens.downloads

/**
 * How the Details tool window is presented, mirroring an IDE tool window's "View Mode". Blaze keeps
 * everything in a single window, so the five modes map onto that reality rather than spawning real
 * OS windows: the two docked modes share the window with the list through a split, the floating
 * modes draw the pane over the list, and "Window" is a docked pane that keeps the larger share.
 */
enum class DetailsViewMode {
    DOCK_PINNED,
    DOCK_UNPINNED,
    UNDOCK,
    FLOAT,
    WINDOW,
    ;

    /** Docked modes split the window beside the list; the rest paint the pane over the list. */
    val isDocked: Boolean get() = this == DOCK_PINNED || this == DOCK_UNPINNED || this == WINDOW

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

/** Rehydrates a persisted view-mode name, falling back to the default for a stale/hand-edited value. */
fun detailsViewModeFromName(name: String): DetailsViewMode =
    DetailsViewMode.entries.firstOrNull { it.name == name } ?: DetailsViewMode.DOCK_PINNED

/** Rehydrates a persisted move-to name, falling back to the current default (Details on the right). */
fun detailsMoveToFromName(name: String): DetailsMoveTo =
    DetailsMoveTo.entries.firstOrNull { it.name == name } ?: DetailsMoveTo.RIGHT_TOP
