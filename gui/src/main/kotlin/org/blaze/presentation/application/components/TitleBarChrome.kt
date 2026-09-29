package org.blaze.presentation.application.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.components.CurrentUiPlatform
import org.blaze.presentation.components.LocalShortcutLabels
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.components.UiPlatform
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
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
    var expanded by remember { mutableStateOf(false) }

    Box {
        ToolbarIconButton(
            key = AllIconsKeys.General.Menu,
            tooltip = menu.mainMenu,
            onClick = { expanded = true },
        )
        if (expanded) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(0, 4),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                MainMenuPopup(
                    onDismiss = { expanded = false },
                    onCommand = { commandBus.send(it) },
                )
            }
        }
    }
}

/**
 * The main-menu card: New Download, Settings, Pause All, Resume All, Toggle Details, About, Exit.
 * Items that have a real, app-wide accelerator (New Download, Settings) show it on the trailing
 * edge; Jewel's plain menu rows expose no built-in accelerator slot, so the shortcut is rendered as
 * secondary text by hand rather than dropped. The rest have no binding, so they omit it.
 */
@Composable
private fun MainMenuPopup(
    onDismiss: () -> Unit,
    onCommand: (AppCommand) -> Unit,
) {
    val menu = blazeStrings.menu
    val shortcuts = LocalShortcutLabels.current
    val shape = RoundedCornerShape(6.dp)

    val run: (AppCommand) -> Unit = { cmd ->
        onCommand(cmd)
        onDismiss()
    }

    Column(
        modifier = Modifier
            .width(240.dp)
            .clip(shape)
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
            .padding(vertical = 4.dp),
    ) {
        MenuRow(menu.newDownload, AllIconsKeys.General.Add, shortcuts.newDownload) { run(AppCommand.NewDownload) }
        MenuRow(menu.settings, AllIconsKeys.General.Settings, shortcuts.settings) { run(AppCommand.OpenSettings) }
        MenuDivider()
        MenuRow(menu.pauseAll, AllIconsKeys.Actions.Pause, null) { run(AppCommand.PauseAll) }
        MenuRow(menu.resumeAll, AllIconsKeys.Actions.Resume, null) { run(AppCommand.ResumeAll) }
        MenuDivider()
        MenuRow(menu.toggleDetails, AllIconsKeys.General.ShowInfos, null) { run(AppCommand.ToggleDetails) }
        MenuRow(menu.about, AllIconsKeys.General.ShowInfos, null) { run(AppCommand.ShowAbout) }
        MenuDivider()
        MenuRow(menu.exit, AllIconsKeys.General.Close, null) { run(AppCommand.Exit) }
    }
}

@Composable
private fun MenuDivider() {
    Divider(
        orientation = Orientation.Horizontal,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun MenuRow(
    text: String,
    icon: IconKey,
    shortcut: String?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) BlazeColors.hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            key = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = JewelTheme.defaultTextStyle,
            color = JewelTheme.globalColors.text.normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (shortcut != null) {
            Text(
                text = shortcut,
                style = JewelTheme.typography.small,
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
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

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)

    Box {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(if (hovered) BlazeColors.hover else Color.Transparent)
                .hoverable(interaction)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                key = if (snail) AllIconsKeys.Actions.Suspend else AllIconsKeys.General.Gear,
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
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(0, 4),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(180.dp)
                        .clip(shape)
                        .background(JewelTheme.globalColors.panelBackground)
                        .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                        .padding(vertical = 4.dp),
                ) {
                    MenuRow(menu.fullSpeed, AllIconsKeys.General.Gear, null) {
                        scope.launch { repository.updateSettings { it.copy(snailModeEnabled = false) } }
                        expanded = false
                    }
                    MenuRow(menu.snailMode, AllIconsKeys.Actions.Suspend, null) {
                        scope.launch { repository.updateSettings { it.copy(snailModeEnabled = true) } }
                        expanded = false
                    }
                }
            }
        }
    }
}

/**
 * The trailing icon cluster: a search button (focuses the list's search field via the bus) and a
 * settings gear, both with their real accelerators in the tooltip. They align to the end, so Jewel
 * lays them out to the left of the auto-emitted window controls — search, then gear, then controls.
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
        ToolbarIconButton(
            key = AllIconsKeys.General.Gear,
            tooltip = menu.settings,
            shortcut = shortcuts.settings,
            onClick = { commandBus.send(AppCommand.OpenSettings) },
        )
    }
}
