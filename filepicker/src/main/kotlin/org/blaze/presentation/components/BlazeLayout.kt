package org.blaze.presentation.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.blaze.presentation.components.BlazeLayout.SettingsDialogWidth

/**
 * Every layout metric the app mirrors from the JetBrains IDE (Int UI) chrome, in one place.
 *
 * These are *structural* dimensions the theme does not expose as tokens (row height, stripe
 * width, status-bar height). Control metrics that Jewel already encodes — button corner radius,
 * progress thickness, list-item padding — are intentionally NOT duplicated here; read them from
 * the component style (e.g. `JewelTheme.simpleListItemStyle.metrics`) per the design rules.
 */
object BlazeLayout {

    /**
     * Structural switch for the IDE's ambient chrome glow — the soft accent-colored field anchored
     * at the window's top-left corner that the title bar, the tool-window stripe and the island
     * gaps sit on (see [org.blaze.presentation.theme.BlazeAmbientGradient]). Flip to false to drop
     * the feature entirely; the user-facing half of it is the "Ambient title-bar glow" preference,
     * folded in through [LocalAmbientGlowEnabled].
     */
    const val USE_AMBIENT_GRADIENT: Boolean = true

    /** Island corner radius, measured off the reference IDE corner (~10-12dp). */
    val IslandRadius = 11.dp

    /** Gap between islands, and between an island and the frame (measured ~2-4dp). */
    val IslandGap = 3.dp

    /** Optional hairline that rims an island (only when the reference shows one). */
    val IslandBorderWidth = 1.dp

    /** Rounded hit target a tool-window stripe button fills inside the 40dp stripe (~28-32dp). */
    val StripeButtonHitTarget = 30.dp

    /** Corner radius of a stripe button's selected/hover background (~6dp). */
    val StripeButtonRadius = 6.dp

    /** Left tool-window stripe: the narrow icon-only strip of view toggles, like IDE tool windows. */
    val ToolWindowStripeWidth = 40.dp

    /** Icon glyph inside the tool-window stripe (the IDE uses 16dp stripes icons in a 40dp gutter). */
    val StripeIconSize = 20.dp

    /** Standard action-toolbar icon size — the IDE renders 16dp glyphs in toolbars. */
    val ToolbarIconSize = 16.dp

    /** Square hit target for a toolbar icon button (IDE action-lookup affordance). */
    val ToolbarButtonHitTarget = 24.dp

    /** Height of the flat bottom status bar (IDE status widget). */
    val StatusBarHeight = 24.dp

    /** Height of a tool-window header row (title + options), like an IDE tool-window title bar. */
    val ToolWindowHeaderHeight = 32.dp

    /** Height of the compact filter/sort bar under a tool-window header. */
    val FilterSortBarHeight = 30.dp

    /** Row height for the download table — the IDE's compact tree/table row. */
    val RowHeight = 24.dp

    /** Leading file/state icon in a download row (IDE tree node icon). */
    val RowLeadingIconSize = 16.dp

    /** Height of a toolbar/action separator's 1dp hairline. */
    val ToolbarSeparatorHeight = 16.dp

    /** Standard content padding inside a panel (IDE grid: 8dp). */
    val ContentPadding = 8.dp

    /** Roomy content padding (IDE panels/dialogs use 8–12). */
    val ContentPaddingLarge = 12.dp

    /** Dialog content padding (DialogWrapper body). */
    val DialogPaddingHorizontal = 20.dp
    val DialogPaddingVertical = 16.dp

    /** Settings/panel max measure width so long forms stay readable, like the IDE editor gutter cap. */
    val FormMaxWidth = 640.dp

    /** In-list search field width in the toolbar. */
    val SearchFieldWidth = 180.dp

    /**
     * Overlay scrollbar track thickness. The IDE's scrollbars are thin, rounded and only present while
     * the pointer is over the scroll area, or it is scrolling (see
     * [org.blaze.presentation.theme.blazeOverlayScrollbarStyle]); Jewel's Windows/Linux default is a
     * permanently visible ~15dp track, which is what the brief rejects.
     */
    val OverlayScrollbarThickness = 7.dp

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

    /** Height of a row in the Settings category list (the IDE's compact list row). */
    val SettingsNavRowHeight = 24.dp

    /** Width of the Settings category list. */
    val SettingsNavWidth = 200.dp

    /**
     * Initial size of the modal Settings window. Wide enough for the category list plus a full
     * settings column at the IDE's comfortable width, and small enough to sit centered over the main
     * frame on a laptop screen without being clamped.
     */
    val SettingsDialogWidth = 920.dp

    /** Initial height of the modal Settings window (see [SettingsDialogWidth]). */
    val SettingsDialogHeight = 640.dp

    /** Invisible drag hit-area around a split divider (Jewel default is 8dp; IDE chrome is thinner). */
    val SplitDragWidth = 8.dp

    /** Height of the download table's column header, matching a data row. */
    val TableHeaderHeight = 24.dp

    /** Hairline weight under the column header and between chrome bands (IDE grid line). */
    val HeaderBorderWidth = 1.dp

    /**
     * The 1dp hairline row Jewel's `TitleBar` always places under the bar. In Islands the bar's own
     * border color is transparent, so this row is the *window's* background showing through: unless
     * something paints it, whatever lies under the Compose surface leaks into it (the Swing default
     * panel color, `#eeeeee`, was exactly that line across the dark theme's glow). The body's frame
     * layer bleeds up by this much to keep the glow continuous.
     */
    val TitleBarBorderHeight = 1.dp
}

/**
 * The download table's columns, left to right. [defaultWidth]/[minWidth] mirror the IDE's resizable
 * table: a column can be dragged narrower than its default but never below [minWidth], and the
 * header and every data row read the same width so cells stay aligned. [id] is the stable key used
 * to persist a user's chosen widths across launches. [endAligned] marks the numeric columns whose
 * header *and* cell right-align together (the IDE aligns a column header to its cell content).
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

/**
 * Whether the app window is the active (focused) window, plumbed down from the
 * [org.jetbrains.jewel.window.DecoratedWindowScope] since Jewel only exposes that state to the
 * title bar (`LocalTitleBarInfo` is internal).
 *
 * Rows and list items read this to pick the IDE's *active* selection color (blue) versus the
 * *inactive-window* selection color (gray), exactly like an IDE table loses its blue highlight
 * when the frame is not focused.
 */
val LocalWindowActive: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/**
 * Whether the ambient glow is drawn, supplied by the window shell from the persisted
 * "Ambient title-bar glow" setting. Kept as a composition local (rather than read straight from
 * the repository) so the frame layer recomposes the instant the preference changes and the
 * rendering helpers stay free of a dependency on the data layer.
 */
val LocalAmbientGlowEnabled: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/**
 * Local shortcut label provider so tooltips and context menus can render the accelerator in the
 * IDE's secondary color without every call site hard-coding the key text. Defaults to empty; the
 * window shell provides the real platform-formatted map.
 */
val LocalShortcutLabels: ProvidableCompositionLocal<ShortcutLabels> = staticCompositionLocalOf {
    ShortcutLabels.None
}

/** Immutable bundle of platform-formatted shortcut strings for the app's global actions. */
data class ShortcutLabels(
    val newDownload: String,
    val search: String,
    /** Opens the Search Everywhere popup (the title-bar search icon), distinct from [search]. */
    val quickSearch: String,
    val selectAll: String,
    val settings: String,
    val togglePause: String,
    val remove: String,
    /** Opens or collapses the Details tool window (the IDE's "show properties" accelerator). */
    val toggleDetails: String,
) {
    companion object {
        /** Blank placeholder used before the shell installs the real, OS-formatted labels. */
        val None = ShortcutLabels("", "", "", "", "", "", "", "")
    }
}
