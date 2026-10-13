package org.blaze.presentation.components

import androidx.compose.ui.unit.dp
import org.island.ui.dialog.IslandDialog

/**
 * Blaze's own layout facts, hoisted out of the shared `IslandLayout` metrics in
 * `:island-theme-ui`. These describe *this app's* screens (the downloads list|details split, the
 * Settings window, the consent dialog) rather than the reusable Islands chrome, so they stay on
 * the consumer side of the boundary.
 */
object AppMetrics {

    /**
     * The Details tool window's share of the list|details split when it first opens, expressed as the
     * *list's* fraction (so the pane gets 1 - this). 0.68 puts the pane at ~32% of the content width,
     * inside the 30-35% the reference shows, and the split's own 320dp pane minimum keeps it usable on
     * narrow windows.
     */
    const val DETAILS_DEFAULT_LIST_FRACTION = 0.68f

    /** Smallest the Details pane may be dragged (the IDE's tool-window minimum). */
    val DetailsPaneMinWidth = 320.dp

    /** Largest the list pane may be squeezed to when the Details pane is widened. */
    val ListPaneMinWidth = 240.dp

    /**
     * Initial size of the modal Settings window. Wide enough for the category list plus a full
     * settings column at the IDE's comfortable width, and small enough to sit centered over the main
     * frame on a laptop screen without being clamped.
     */
    val SettingsDialogWidth = 920.dp

    /** Initial height of the modal Settings window (see [SettingsDialogWidth]). */
    val SettingsDialogHeight = 640.dp

    /** Height of a row in the Settings category list (the IDE's compact list row). */
    val SettingsNavRowHeight = 24.dp

    /** Width of the Settings category list. */
    val SettingsNavWidth = 200.dp

    /**
     * Width of the security consent dialog. It is a pure reading task — no form, no list — so it
     * stays at the plain-dialog width [IslandDialog] uses, letting the multi-paragraph warning copy
     * wrap at a comfortable measure.
     */
    val ConsentDialogWidth = 460.dp
}
