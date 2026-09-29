package org.blaze.presentation.application.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.components.BlazeIcons
import org.blaze.presentation.components.BlazeKeymap
import org.blaze.presentation.components.BlazeMenu
import org.blaze.presentation.components.CurrentUiPlatform
import org.blaze.presentation.components.LocalShortcutLabels
import org.blaze.presentation.components.MenuEntry
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.components.UiPlatform
import org.blaze.presentation.components.rememberMenuAnchor
import org.blaze.presentation.components.trackingModifier
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import org.koin.compose.koinInject

/**
 * The leading hamburger button that opens the app-level main menu. Windows and Linux get it (their
 * windows have no native application menu); macOS keeps using the native bar, so this is a no-op
 * there. It lives in the window chrome, so every entry raises an [AppCommand] on the shared bus
 * rather than reaching into whichever screen happens to be visible.
 */
@Composable
fun HamburgerMenuButton(commandBus: AppCommandBus) {
    if (CurrentUiPlatform == UiPlatform.macOS) return
    val menu = blazeStrings.menu
    val selection = blazeStrings.downloads.selection
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberMenuAnchor()

    Box(modifier = anchor.trackingModifier) {
        ToolbarIconButton(
            key = AllIconsKeys.General.Menu,
            tooltip = menu.mainMenu,
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            // The menu used to be a hand-styled Column of rows with a 6dp shape and literal paddings
            // painted over the panel color; it is now the same BlazeMenu the row context menu uses, so
            // it takes its radius, border, shadow, row height, hover and text colors from the theme's
            // menu tokens like every other popup, and submenus behave the same everywhere.
            BlazeMenu(
                entries = buildList {
                    add(
                        MenuEntry.Action(
                            label = menu.newDownload,
                            icon = AllIconsKeys.General.Add,
                            accelerator = BlazeKeymap.displayValue(BlazeKeymap.Action.NewDownload),
                            onClick = { commandBus.send(AppCommand.NewDownload) },
                        ),
                    )
                    add(
                        MenuEntry.Action(
                            label = menu.search,
                            icon = AllIconsKeys.Actions.Find,
                            // The in-list search field, which is what Ctrl/Cmd+F really does from here;
                            // the Search Everywhere popup gets its own entry (and its own chord) in step B.
                            accelerator = BlazeKeymap.displayValue(BlazeKeymap.Action.FocusListSearch),
                            onClick = { commandBus.send(AppCommand.FocusSearch) },
                        ),
                    )
                    add(
                        MenuEntry.Action(
                            label = menu.settings,
                            icon = AllIconsKeys.General.Settings,
                            accelerator = BlazeKeymap.displayValue(BlazeKeymap.Action.OpenSettings),
                            onClick = { commandBus.send(AppCommand.OpenSettings) },
                        ),
                    )
                    add(MenuEntry.Separator)
                    add(
                        MenuEntry.Action(
                            label = menu.pauseAll,
                            icon = AllIconsKeys.Actions.Pause,
                            onClick = { commandBus.send(AppCommand.PauseAll) },
                        ),
                    )
                    add(
                        MenuEntry.Action(
                            label = menu.resumeAll,
                            icon = AllIconsKeys.Actions.Resume,
                            onClick = { commandBus.send(AppCommand.ResumeAll) },
                        ),
                    )
                    add(
                        MenuEntry.Action(
                            label = selection.selectAll,
                            icon = AllIconsKeys.Actions.Selectall,
                            accelerator = BlazeKeymap.displayValue(BlazeKeymap.Action.SelectAll),
                            onClick = { commandBus.send(AppCommand.SelectAll) },
                        ),
                    )
                    add(MenuEntry.Separator)
                    add(
                        MenuEntry.Action(
                            label = menu.toggleDetails,
                            icon = AllIconsKeys.General.ShowInfos,
                            accelerator = BlazeKeymap.displayValue(BlazeKeymap.Action.ToggleDetails),
                            onClick = { commandBus.send(AppCommand.ToggleDetails) },
                        ),
                    )
                    add(
                        MenuEntry.Action(
                            label = menu.about,
                            icon = AllIconsKeys.General.ShowInfos,
                            onClick = { commandBus.send(AppCommand.ShowAbout) },
                        ),
                    )
                    add(MenuEntry.Separator)
                    add(
                        MenuEntry.Action(
                            label = menu.exit,
                            icon = AllIconsKeys.General.Close,
                            onClick = { commandBus.send(AppCommand.Exit) },
                        ),
                    )
                },
                anchor = anchor,
                onDismissRequest = { expanded = false },
            )
        }
    }
}

/**
 * One IDE-style title-bar widget: icon + text + chevron with a subtle hover fill and ~6dp radius.
 * It is backed by a real setting — Snail Mode (the engine's global speed throttle) — so toggling it
 * writes through [EngineSettingsRepository] exactly like the tray and settings screen do. The widget
 * simply reflects and flips that persisted flag; no unbacked fake control.
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
                .background(if (hovered) BlazeColors.hover else Color.Transparent)
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
            // listed — and the popup is the shared BlazeMenu, not a second hand-drawn menu surface.
            BlazeMenu(
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

/**
 * The trailing icon cluster: the search button. It aligns to the end, so Jewel lays it out to the
 * left of the auto-emitted window controls.
 *
 * There is deliberately no settings gear here: the tool-window stripe already carries one at its
 * bottom, and the IDE keeps exactly one entry point per view — two gears, one window apart, is the
 * duplicate the reference never shows.
 */
@Composable
fun TitleBarTrailingIcons(commandBus: AppCommandBus) {
    val menu = blazeStrings.menu
    val shortcuts = LocalShortcutLabels.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolbarIconButton(
            key = AllIconsKeys.Actions.Find,
            tooltip = menu.search,
            shortcut = shortcuts.search,
            onClick = { commandBus.send(AppCommand.FocusSearch) },
        )
    }
}
