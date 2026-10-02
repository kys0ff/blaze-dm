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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.components.BlazeIcons
import org.blaze.presentation.components.BlazeMenu
import org.blaze.presentation.components.CurrentUiPlatform
import org.blaze.presentation.components.MenuEntry
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.components.UiPlatform
import org.blaze.presentation.components.rememberMenuAnchor
import org.blaze.presentation.components.trackingModifier
import org.blaze.presentation.search.AppActions
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import org.koin.compose.koinInject

enum class TitleBarMenuCategory {
    File,
    Edit,
    View,
    Tools,
    Help,
}

private fun categoryLabel(category: TitleBarMenuCategory, strings: BlazeStrings): String = when (category) {
    TitleBarMenuCategory.File -> strings.menu.file
    TitleBarMenuCategory.Edit -> strings.menu.edit
    TitleBarMenuCategory.View -> strings.menu.view
    TitleBarMenuCategory.Tools -> strings.menu.tools
    TitleBarMenuCategory.Help -> strings.menu.help
}

internal fun entriesForCategory(
    category: TitleBarMenuCategory,
    strings: BlazeStrings,
    commandBus: AppCommandBus,
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
        )

        TitleBarMenuCategory.Help -> listOf(
            findAction(AppCommand.ShowAbout),
        )
    }
}

/**
 * The leading hamburger button / title-bar menu headers. Windows and Linux get it (their
 * windows have no native application menu); macOS keeps using the native bar, so this is a no-op
 * there. Clicking the hamburger button hides it and reveals the title-bar menu headers
 * ("File", "Edit", "View", "Tools", "Help"). Hovering across headers switches the open menu;
 * clicking a menu command or clicking outside closes the title-bar menu items and restores
 * the hamburger button.
 */
@Composable
fun HamburgerMenuButton(
    commandBus: AppCommandBus,
    onExpandChange:(Boolean)->Unit
) {
    if (CurrentUiPlatform == UiPlatform.MacOS) return
    val strings = blazeStrings
    var isExpanded by remember { mutableStateOf(false) }
    var activeCategory by remember { mutableStateOf<TitleBarMenuCategory?>(null) }

    val fileAnchor = rememberMenuAnchor()
    val editAnchor = rememberMenuAnchor()
    val viewAnchor = rememberMenuAnchor()
    val toolsAnchor = rememberMenuAnchor()
    val helpAnchor = rememberMenuAnchor()

    val categoryAnchors = remember(fileAnchor, editAnchor, viewAnchor, toolsAnchor, helpAnchor) {
        mapOf(
            TitleBarMenuCategory.File to fileAnchor,
            TitleBarMenuCategory.Edit to editAnchor,
            TitleBarMenuCategory.View to viewAnchor,
            TitleBarMenuCategory.Tools to toolsAnchor,
            TitleBarMenuCategory.Help to helpAnchor,
        )
    }
    val headerBoundsMap = remember { mutableStateMapOf<TitleBarMenuCategory, Rect>() }

    if (!isExpanded) {
        val anchor = rememberMenuAnchor()
        Box(modifier = anchor.trackingModifier) {
            ToolbarIconButton(
                key = AllIconsKeys.General.Menu,
                tooltip = strings.menu.mainMenu,
                onClick = {
                    isExpanded = true
                    activeCategory = TitleBarMenuCategory.File
                    onExpandChange(isExpanded)
                },
            )
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (category in TitleBarMenuCategory.entries) {
                val anchor = categoryAnchors.getValue(category)
                val isActive = activeCategory == category

                Box(
                    modifier = anchor.trackingModifier.onGloballyPositioned { coords ->
                        headerBoundsMap[category] = coords.boundsInRoot()
                    },
                ) {
                    val shape = RoundedCornerShape(4.dp)
                    Box(
                        modifier = Modifier
                            .clip(shape)
                            .background(if (isActive) BlazeColors.hover else Color.Transparent)
                            .clickable {
                                if (isActive) {
                                    isExpanded = false
                                    activeCategory = null
                                    onExpandChange(isExpanded)
                                } else {
                                    activeCategory = category
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = categoryLabel(category, strings),
                            style = JewelTheme.typography.small,
                            color = JewelTheme.globalColors.text.normal,
                            maxLines = 1,
                        )
                    }

                    if (isActive) {
                        BlazeMenu(
                            entries = entriesForCategory(category, strings, commandBus) {
                                isExpanded = false
                                activeCategory = null
                                onExpandChange(isExpanded)
                            },
                            anchor = anchor,
                            onDismissRequest = {
                                isExpanded = false
                                activeCategory = null
                                onExpandChange(isExpanded)
                            },
                            headerBounds = headerBoundsMap,
                            onHeaderHovered = { hoveredCat ->
                                if (hoveredCat is TitleBarMenuCategory && activeCategory != hoveredCat) {
                                    activeCategory = hoveredCat
                                }
                            },
                            onHeaderClicked = { clickedCat ->
                                if (clickedCat is TitleBarMenuCategory) {
                                    if (activeCategory == clickedCat) {
                                        isExpanded = false
                                        activeCategory = null
                                        onExpandChange(isExpanded)
                                    } else {
                                        activeCategory = clickedCat
                                    }
                                }
                            },
                        )
                    }
                }
            }
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

