package org.island.ui.text

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The user-facing text the island components render, as an injectable contract.
 *
 * Island modules never depend on an app's i18n layer: every component that shows a label takes it
 * from [LocalIslandUiStrings] instead. The defaults here are plain English (the wording the JetBrains
 * Islands UI itself uses) so a fresh app gets a working, readable shell without wiring anything; a
 * localized host (like Blaze's `i18n` module) supplies an implementation adapting its own strings and
 * provides it at the window root.
 */
interface IslandUiStrings {
    // ── Search Everywhere ─────────────────────────────────────────────────────────────────────────

    /** Placeholder shown in an empty Search Everywhere field. */
    fun searchEverywherePlaceholder(): String = "Type to search…"

    /** Hint shown instead of a list when the query is empty and the tab has no meaningful default. */
    fun searchTypeToSearch(): String = "Type to search"

    /** Shown when a non-empty query matches nothing. */
    fun searchNothingFound(): String = "Nothing found"

    /** Label of Search Everywhere's built-in tab that interleaves every result family. */
    fun searchTabAll(): String = "All"

    // ── Status bar ────────────────────────────────────────────────────────────────────────────────

    /** Status-bar label for a live connection. */
    fun connected(): String = "Connected"

    /** Status-bar label for a dropped connection. */
    fun offline(): String = "Offline"

    // ── Notification balloons ─────────────────────────────────────────────────────────────────────

    /** Link that puts an error balloon's message on the clipboard. */
    fun copyDetails(): String = "Copy details"

    /** Accessibility label for a close button (balloons, popups, tool windows). */
    fun close(): String = "Close"

    // ── Tool-window chrome ────────────────────────────────────────────────────────────────────────

    /** Tooltip of the floating window's hide button. */
    fun toolWindowHide(): String = "Hide"

    /** The whole label bundle of the tool-window "more options" menu. */
    val toolWindowOptions: IslandToolWindowOptionStrings
        get() = IslandToolWindowOptionStrings.Default

    // ── Icons ─────────────────────────────────────────────────────────────────────────────────────

    /** Accessibility label for an extension-supplied icon. */
    fun extensionIcon(): String = "Extension icon"

    // ── Window shell (used by the shell layer) ────────────────────────────────────────────────────

    /** Accessibility label for the window's hamburger (system) menu button. */
    fun systemMenu(): String = "System menu"

    // ── Window system menu (opened from the title-bar app icon) ─────────────────────────────────

    /** Row of the OS-style system menu that undoes a maximize. */
    fun windowRestore(): String = "Restore"

    /** Row of the OS-style system menu that minimizes (or hides) the frame. */
    fun windowMinimize(): String = "Minimize"

    /** Row of the OS-style system menu that maximizes the frame. */
    fun windowMaximize(): String = "Maximize"

    /** Row of the OS-style system menu that closes the frame. */
    fun windowClose(): String = "Close"

    // ── Title-bar hamburger / main-menu affordance ────────────────────────────────────────────────

    /** Tooltip of the collapsed title-bar hamburger button that opens the app's main menu. */
    fun mainMenu(): String = "Main Menu"

    // ── Whole-window drop target ──────────────────────────────────────────────────────────────────

    /** Headline of the drop overlay while a payload the app accepts hovers the window. */
    fun dropOverlayTitle(): String = "Drop to add"

    /** Headline of the drop overlay while a payload the app cannot use hovers the window. */
    fun dropOverlayUnsupportedTitle(): String = "Can't drop that here"

    /** Suffix shown after the preview names when more items are in flight than the overlay lists. */
    fun dropItemsMore(count: Int): String = "+$count more"

    /** Balloon reported when [count] items from a drop were accepted by the app. */
    fun dropQueued(count: Int): String =
        if (count == 1) "1 item accepted" else "$count items accepted"

    /** Balloon reported with the truncated names of items a drop could not use. */
    fun dropRejected(names: String): String = "Can't use: $names"

    /** Balloon reported when a drop carried no item the app could read. */
    fun dropNothingUsable(): String = "Nothing in that drop can be used"

    companion object {
        /** The stock English instance used when no localized provider is installed. */
        val Default: IslandUiStrings = object : IslandUiStrings {}
    }
}

/**
 * Every label of a dockable tool window's options menu, in the shape the IDE draws it: a tooltip for
 * the "more" button, a group-tabs toggle, and the View Mode / Move To / Resize submenus.
 */
interface IslandToolWindowOptionStrings {
    /** Tooltip for the button that opens the options menu. */
    fun menuTooltip(): String = "Tool Window Options"

    /** Toggle that stacks the pane's tabs into one continuous view. */
    fun groupTabs(): String = "Group Tabs"

    fun viewMode(): String = "View Mode"
    fun moveTo(): String = "Move To"
    fun resize(): String = "Resize"

    // View Mode
    fun dockPinned(): String = "Dock Pinned"
    fun dockUnpinned(): String = "Dock Unpinned"
    fun undock(): String = "Undock"
    fun float(): String = "Float"
    fun window(): String = "Window"

    // Move To
    fun leftTop(): String = "Left Top"
    fun leftBottom(): String = "Left Bottom"
    fun rightTop(): String = "Right Top"
    fun rightBottom(): String = "Right Bottom"
    fun bottomLeft(): String = "Bottom Left"
    fun bottomRight(): String = "Bottom Right"

    // Resize
    fun stretchLeft(): String = "Stretch to Left"
    fun stretchRight(): String = "Stretch to Right"
    fun stretchTop(): String = "Stretch to Top"
    fun stretchBottom(): String = "Stretch to Bottom"
    fun maximize(): String = "Maximize Tool Window"

    companion object {
        val Default: IslandToolWindowOptionStrings = object : IslandToolWindowOptionStrings {}
    }
}

/**
 * CompositionLocal carrying the active [IslandUiStrings]. Apps override it at the window root
 * (the shell provides it); components read only this, never a concrete i18n engine.
 */
val LocalIslandUiStrings: ProvidableCompositionLocal<IslandUiStrings> =
    staticCompositionLocalOf { IslandUiStrings.Default }
