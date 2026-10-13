package org.blaze.presentation.components

import androidx.compose.runtime.Stable
import org.blaze.i18n.BlazeStrings
import org.island.ui.text.IslandToolWindowOptionStrings
import org.island.ui.text.IslandUiStrings

/**
 * Adapts Blaze's live [BlazeStrings] to the island components' [IslandUiStrings] contract, so every
 * moved component renders exactly the text it rendered before the extraction. Provided at the window
 * root (see BlazeWindow) and inside the Search Everywhere popup; the *instance* is keyed on the
 * strings object, and each accessor reads through to the live i18n state, so a language switch
 * recomposes island chrome without re-providing anything.
 */
@Stable
class BlazeIslandStrings(val blaze: BlazeStrings) : IslandUiStrings {

    override fun searchEverywherePlaceholder() = blaze.search.placeholder
    override fun searchTypeToSearch() = blaze.search.typeToSearch
    override fun searchNothingFound() = blaze.search.nothingFound
    override fun searchTabAll() = blaze.search.tabAll

    override fun connected() = blaze.common.connected
    override fun offline() = blaze.common.offline

    override fun copyDetails() = blaze.common.copyDetails
    override fun close() = blaze.common.close

    override fun toolWindowHide() = blaze.downloads.detailsPane.hide
    override val toolWindowOptions: IslandToolWindowOptionStrings
        get() = BlazeToolWindowOptionStrings(blaze.downloads.detailsPane.options)

    override fun extensionIcon() = blaze.common.extensionIcon

    override fun systemMenu() = blaze.menu.mainMenu

    // ── Window system menu (shell) ────────────────────────────────────────────────────────────────
    override fun windowRestore() = blaze.menu.windowRestore
    override fun windowMinimize() = blaze.menu.windowMinimize
    override fun windowMaximize() = blaze.menu.windowMaximize
    override fun windowClose() = blaze.menu.windowClose

    // ── Title-bar hamburger (shell) ───────────────────────────────────────────────────────────────
    override fun mainMenu() = blaze.menu.mainMenu

    // ── Whole-window drop overlay + balloons (shell) ──────────────────────────────────────────────
    override fun dropOverlayTitle() = blaze.downloads.drop.overlayTitle
    override fun dropOverlayUnsupportedTitle() = blaze.downloads.drop.overlayUnsupportedTitle
    override fun dropItemsMore(count: Int) = blaze.downloads.drop.itemsMore(count)
    override fun dropQueued(count: Int) = blaze.downloads.drop.queued(count)
    override fun dropRejected(names: String) = blaze.downloads.drop.rejected(names)
    override fun dropNothingUsable() = blaze.downloads.drop.nothingUsable

    /** The tool-window options menu labels, mapped from Blaze's `detailsPane.options` bundle. */
    @Stable
    private class BlazeToolWindowOptionStrings(
        private val o: BlazeStrings.Downloads.DetailsPane.ToolWindowOptions,
    ) : IslandToolWindowOptionStrings {
        override fun menuTooltip() = o.menuTooltip
        override fun groupTabs() = o.groupTabs
        override fun viewMode() = o.viewMode
        override fun moveTo() = o.moveTo
        override fun resize() = o.resize
        override fun dockPinned() = o.dockPinned
        override fun dockUnpinned() = o.dockUnpinned
        override fun undock() = o.undock
        override fun float() = o.float
        override fun window() = o.window
        override fun leftTop() = o.leftTop
        override fun leftBottom() = o.leftBottom
        override fun rightTop() = o.rightTop
        override fun rightBottom() = o.rightBottom
        override fun bottomLeft() = o.bottomLeft
        override fun bottomRight() = o.bottomRight
        override fun stretchLeft() = o.stretchLeft
        override fun stretchRight() = o.stretchRight
        override fun stretchTop() = o.stretchTop
        override fun stretchBottom() = o.stretchBottom
        override fun maximize() = o.maximize
    }
}
