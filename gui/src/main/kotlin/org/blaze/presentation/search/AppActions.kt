package org.blaze.presentation.search

import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.components.BlazeKeymap
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The app's window-level commands, in one registry the title-bar hamburger menu *and* Search
 * Everywhere's Actions tab both build from. This is what keeps the two honest: an action can appear in
 * the searchable list only because it already appears — with the same label, icon and accelerator — in
 * the menu the user can click, so nothing is offered in search that is not reachable elsewhere with
 * identical behavior (the rule the rest of the shell follows).
 *
 * [group] drives where the hamburger draws its separators; [searchable] is false only for
 * [AppCommand.SearchEverywhere] itself, which opens the popup rather than being something to find *in*
 * it. [acceleratorAction] is the [BlazeKeymap.Action] whose OS-formatted chord the row prints; null for
 * the commands that have no keybinding (Pause All, Resume All, About, Exit).
 */
object AppActions {

    data class AppAction(
        val command: AppCommand,
        val label: (BlazeStrings) -> String,
        val icon: IconKey,
        val acceleratorAction: BlazeKeymap.Action?,
        val group: Int,
        val searchable: Boolean = true,
    )

    val all: List<AppAction> = listOf(
        AppAction(
            command = AppCommand.NewDownload,
            label = { it.menu.newDownload },
            icon = AllIconsKeys.General.Add,
            acceleratorAction = BlazeKeymap.Action.NewDownload,
            group = 0,
        ),
        AppAction(
            command = AppCommand.SearchEverywhere,
            label = { it.menu.searchEverywhere },
            icon = AllIconsKeys.Actions.Find,
            acceleratorAction = BlazeKeymap.Action.SearchEverywhere,
            group = 0,
            searchable = false,
        ),
        AppAction(
            command = AppCommand.OpenSettings,
            label = { it.menu.settings },
            icon = AllIconsKeys.General.Settings,
            acceleratorAction = BlazeKeymap.Action.OpenSettings,
            group = 0,
        ),
        AppAction(
            command = AppCommand.PauseAll,
            label = { it.menu.pauseAll },
            icon = AllIconsKeys.Actions.Pause,
            acceleratorAction = null,
            group = 1,
        ),
        AppAction(
            command = AppCommand.ResumeAll,
            label = { it.menu.resumeAll },
            icon = AllIconsKeys.Actions.Resume,
            acceleratorAction = null,
            group = 1,
        ),
        AppAction(
            command = AppCommand.SelectAll,
            label = { it.downloads.selection.selectAll },
            icon = AllIconsKeys.Actions.Selectall,
            acceleratorAction = BlazeKeymap.Action.SelectAll,
            group = 1,
        ),
        AppAction(
            command = AppCommand.ToggleDetails,
            label = { it.menu.toggleDetails },
            icon = AllIconsKeys.General.ShowInfos,
            acceleratorAction = BlazeKeymap.Action.ToggleDetails,
            group = 2,
        ),
        AppAction(
            command = AppCommand.ShowAbout,
            label = { it.menu.about },
            icon = AllIconsKeys.General.ShowInfos,
            acceleratorAction = null,
            group = 2,
        ),
        AppAction(
            command = AppCommand.Exit,
            label = { it.menu.exit },
            icon = AllIconsKeys.General.Close,
            acceleratorAction = null,
            group = 3,
        ),
    )

    /** The commands Search Everywhere may offer (the popup's own trigger is excluded). */
    val searchable: List<AppAction> = all.filter { it.searchable }

    /** The formatted accelerator a row prints beside an action, or null when it has no chord. */
    fun accelerator(action: AppAction): String? =
        action.acceleratorAction?.let { BlazeKeymap.displayValue(it) }
}
