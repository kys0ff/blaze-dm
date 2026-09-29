package org.blaze.presentation.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
     * The one switch for the JetBrains New UI "Islands" shell: when true the window paints a
     * neutral frame and every region floats as a rounded island separated by gaps (no hairline
     * dividers between major regions). Flip to false to restore the classic flat, divider-lined
     * look — no other call site changes are needed.
     */
    const val useIslands: Boolean = true

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
    val ToolbarSeparatorHeight = 16.dp // TODO: make use of this

    /** Standard content padding inside a panel (IDE grid: 8dp). */
    val ContentPadding = 8.dp

    /** Roomy content padding (IDE panels/dialogs use 8–12). */
    val ContentPaddingLarge = 12.dp

    /** Dialog content padding (DialogWrapper body). */
    val DialogPaddingHorizontal = 20.dp // TODO: make use of this
    val DialogPaddingVertical = 16.dp // TODO: make use of this

    /** Settings/panel max measure width so long forms stay readable, like the IDE editor gutter cap. */
    val FormMaxWidth = 640.dp // TODO: make use of this

    /** In-list search field width in the toolbar. */
    val SearchFieldWidth = 220.dp // TODO: make use of this

    /** Invisible drag hit-area around a split divider (Jewel default is 8dp; IDE chrome is thinner). */
    val SplitDragWidth = 8.dp

    /** Height of the download table's column header, matching a data row. */
    val TableHeaderHeight = 24.dp

    /** Hairline weight under the column header and between chrome bands (IDE grid line). */
    val HeaderBorderWidth = 1.dp
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
    val selectAll: String,
    val settings: String,
    val togglePause: String,
    val remove: String,
) {
    companion object {
        /** Blank placeholder used before the shell installs the real, OS-formatted labels. */
        val None = ShortcutLabels("", "", "", "", "", "")
    }
}
