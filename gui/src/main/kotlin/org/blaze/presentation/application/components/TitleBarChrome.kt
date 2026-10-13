package org.blaze.presentation.application.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.components.BlazeIcons
import org.blaze.presentation.search.AppActions
import org.island.theme.ui.IslandSemanticColors
import org.island.ui.menu.IslandMenu
import org.island.ui.menu.MenuEntry
import org.island.ui.menu.rememberMenuAnchor
import org.island.ui.menu.trackingModifier
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import org.koin.compose.koinInject

/**
 * The five title-bar menu headers Blaze draws. Their rendering lives in the shell
 * ([IslandMenuCategory] + [IslandHamburgerMenu][org.island.shell.IslandHamburgerMenu]); this enum
 * exists only to bind each header to Blaze's own command list, so the shell never has to know Blaze
 * commands or the shape of Blaze's i18n bundle.
 */
enum class TitleBarMenuCategory {
    File,
    Edit,
    View,
    Tools,
    Help,
}

internal fun categoryLabel(category: TitleBarMenuCategory, strings: BlazeStrings): String = when (category) {
    TitleBarMenuCategory.File -> strings.menu.file
    TitleBarMenuCategory.Edit -> strings.menu.edit
    TitleBarMenuCategory.View -> strings.menu.view
    TitleBarMenuCategory.Tools -> strings.menu.tools
    TitleBarMenuCategory.Help -> strings.menu.help
}

/**
 * Blaze's per-category menu rows. Pure: takes the state it needs (strings, command bus, server
 * running flag) and returns the rows; the `onExecuted` closure is what the shell fires after any
 * row's click so the popup folds back to the collapsed hamburger button.
 */
internal fun entriesForCategory(
    category: TitleBarMenuCategory,
    strings: BlazeStrings,
    commandBus: AppCommandBus,
    serverRunning: Boolean,
    onExecuted: () -> Unit,
): List<MenuEntry> {
    fun AppActions.AppAction.toMenuEntry(): MenuEntry.Action =
        MenuEntry.Action(
            label = label(strings),
            icon = icon,
            accelerator = AppActions.accelerator(this),
            onClick = {
                commandBus.send(command)
                onExecuted()
            },
        )

    fun findAction(command: AppCommand) =
        AppActions.all.first { it.command == command }.toMenuEntry()

    return when (category) {
        TitleBarMenuCategory.File -> listOf(
            findAction(AppCommand.NewDownload),
            MenuEntry.Separator,
            findAction(AppCommand.OpenSettings),
            MenuEntry.Separator,
            findAction(AppCommand.Exit),
        )

        TitleBarMenuCategory.Edit -> listOf(
            findAction(AppCommand.SelectAll),
        )

        TitleBarMenuCategory.View -> listOf(
            findAction(AppCommand.ToggleDetails),
        )

        TitleBarMenuCategory.Tools -> listOf(
            findAction(AppCommand.PauseAll),
            findAction(AppCommand.ResumeAll),
            MenuEntry.Separator,
            findAction(AppCommand.SearchEverywhere),
            MenuEntry.Separator,
            // The Server Logs window and the local API server's lifecycle. The submenu's Start / Stop /
            // Restart rows mirror the running state so an impossible transition is disabled, not hidden -
            // the same rule the tray's transport items follow.
            MenuEntry.Action(
                label = strings.menu.serverLogs,
                icon = AllIconsKeys.Nodes.Console,
                onClick = {
                    commandBus.send(AppCommand.ShowServerLog)
                    onExecuted()
                },
            ),
            MenuEntry.Submenu(
                label = strings.menu.server,
                icon = AllIconsKeys.Actions.Execute,
                children = listOf(
                    MenuEntry.Action(
                        label = strings.menu.startServer,
                        icon = AllIconsKeys.Actions.Execute,
                        enabled = !serverRunning,
                        onClick = {
                            commandBus.send(AppCommand.StartServer)
                            onExecuted()
                        },
                    ),
                    MenuEntry.Action(
                        label = strings.menu.stopServer,
                        icon = AllIconsKeys.Run.Stop,
                        enabled = serverRunning,
                        onClick = {
                            commandBus.send(AppCommand.StopServer)
                            onExecuted()
                        },
                    ),
                    MenuEntry.Action(
                        label = strings.menu.restartServer,
                        icon = AllIconsKeys.Actions.Restart,
                        enabled = serverRunning,
                        onClick = {
                            commandBus.send(AppCommand.RestartServer)
                            onExecuted()
                        },
                    ),
                    MenuEntry.Separator,
                    MenuEntry.Action(
                        label = strings.menu.copyEndpoint,
                        icon = AllIconsKeys.Actions.Copy,
                        enabled = serverRunning,
                        onClick = {
                            commandBus.send(AppCommand.CopyServerEndpoint)
                            onExecuted()
                        },
                    ),
                ),
            ),
        )

        TitleBarMenuCategory.Help -> listOf(
            findAction(AppCommand.ShowAbout),
        )
    }
}

/**
 * One IDE-style title-bar widget: icon + text + chevron with a subtle hover fill and ~6dp radius.
 * It is backed by a real setting — Snail Mode (the engine's global speed throttle) — so toggling it
 * writes through [EngineSettingsRepository] exactly like the tray and settings screen do. The widget
 * simply reflects and flips that persisted flag; no unbacked fake control.
 *
 * Lives in the title bar's leading cluster; the shell hides it automatically while the hamburger
 * is expanded (see [IslandTitleBar.leadingCollapsedContent]).
 */
@Composable
fun SpeedLimitWidget() {
    val menu = blazeStrings.menu
    val repository = koinInject<EngineSettingsRepository>()
    val settings by repository.settings.collectAsState()
    val snail = settings.snailModeEnabled
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberMenuAnchor()

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)

    Box(modifier = anchor.trackingModifier) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(if (hovered) IslandSemanticColors.hover else Color.Transparent)
                .hoverable(interaction)
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                // The widget's own glyph is a speed glyph, never the settings gear it used to borrow: the
                // title bar already ends with a real Settings gear, and two of them read as two commands.
                key = if (snail) BlazeIcons.SnailMode else BlazeIcons.FullSpeed,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = if (snail) menu.snailMode else menu.fullSpeed,
                style = JewelTheme.typography.small,
                color = JewelTheme.globalColors.text.normal,
                maxLines = 1,
            )
            Icon(
                key = if (expanded) AllIconsKeys.General.ChevronUp else AllIconsKeys.General.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
        }
        if (expanded) {
            // The two speed modes are one choice, so the active one is marked rather than merely
            // listed — and the popup is the shared IslandMenu, not a second hand-drawn menu surface.
            IslandMenu(
                entries = listOf(
                    MenuEntry.Action(
                        label = menu.fullSpeed,
                        icon = BlazeIcons.FullSpeed,
                        checked = !snail,
                        onClick = { scope.launch { repository.updateSettings { it.copy(snailModeEnabled = false) } } },
                    ),
                    MenuEntry.Action(
                        label = menu.snailMode,
                        icon = BlazeIcons.SnailMode,
                        checked = snail,
                        onClick = { scope.launch { repository.updateSettings { it.copy(snailModeEnabled = true) } } },
                    ),
                ),
                anchor = anchor,
                onDismissRequest = { expanded = false },
            )
        }
    }
}

