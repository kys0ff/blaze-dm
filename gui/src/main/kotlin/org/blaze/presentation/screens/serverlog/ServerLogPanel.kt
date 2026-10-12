package org.blaze.presentation.screens.serverlog

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.logging.LogEntry
import org.blaze.logging.ServerLogRepository
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.components.BlazeKeymap
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.LocalShortcutLabels
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.components.ToolWindowOptionsButton
import org.blaze.presentation.components.ToolWindowSearchField
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.components.panel.ToolWindowLayoutAction
import org.blaze.presentation.theme.BlazeColors
import org.blaze.server.DownloadServiceController
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * The Server Logs tool window: the running process's log stream (which carries the local API server's own
 * lines) drawn like an IDE Log window, with live server controls in the header and an IntelliJ-style
 * in-panel find bar. Docking is hoisted to [org.blaze.presentation.screens.main.MainScreen]; this renders
 * the window's own chrome and body and never decides where it sits.
 *
 * Find is scoped to this panel: the root takes focus and installs a preview key handler, so Ctrl+F opens
 * the find bar only while this subtree holds focus (the downloads list keeps its own global Ctrl+F), and
 * Enter / Shift+Enter / Escape drive match navigation while the bar is open.
 */
@Composable
fun ServerLogPanel(
    model: ServerLogWindowModel,
    repository: ServerLogRepository,
    controller: DownloadServiceController,
    commandBus: AppCommandBus,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = blazeStrings
    val log = strings.serverLog
    val options = strings.downloads.detailsPane.options
    val shortcuts = LocalShortcutLabels.current
    val state by model.state.collectAsState()
    val entries by repository.entries.collectAsState()
    val autoScroll by repository.autoScroll.collectAsState()
    val status by controller.status.collectAsState()
    val running = status.running

    val listState = rememberLazyListState()
    val findFocusRequester = remember { FocusRequester() }

    // Every occurrence of the query across every buffered line, as (line index, range within the line),
    // in read order — the flat list is what the counter and next/previous navigate.
    val matches = remember(entries, state.findQuery) {
        val query = state.findQuery
        if (query.isBlank()) {
            emptyList()
        } else {
            buildList {
                entries.forEachIndexed { index, entry ->
                    matchRanges(entry.render(), query).forEach { add(index to it) }
                }
            }
        }
    }

    LaunchedEffect(matches.size) { model.setMatches(matches.size) }
    LaunchedEffect(state.findBarOpen) { if (state.findBarOpen) runCatching { findFocusRequester.requestFocus() } }
    // Follow the newest line while auto-scroll is on, and jump to the focused match when it moves.
    LaunchedEffect(entries.size, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    LaunchedEffect(state.currentMatch, matches) {
        matches.getOrNull(state.currentMatch)?.let { listState.animateScrollToItem(it.first) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val findChord = with(BlazeKeymap) { BlazeKeymap.Binding(Key.F).matches(event) }
                when {
                    findChord -> { model.toggleFind(); true }
                    state.findBarOpen && event.key == Key.Enter -> {
                        if (event.isShiftPressed) model.previousMatch() else model.nextMatch()
                        true
                    }

                    state.findBarOpen && event.key == Key.Escape -> { model.closeFind(); true }
                    else -> false
                }
            },
    ) {
        ToolWindowHeader(
            title = log.title,
            actions = {
                // Live server status word, then the quick lifecycle toggles routed through the same
                // commands the Tools menu raises so the shell is the only place that touches the launcher.
                Text(
                    text = if (running) log.running else log.stopped,
                    style = JewelTheme.typography.small,
                    color = if (running) BlazeColors.accent else JewelTheme.globalColors.text.info,
                    modifier = Modifier.padding(end = 6.dp),
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.Execute,
                    tooltip = log.startAction,
                    enabled = !running,
                    onClick = { commandBus.send(AppCommand.StartServer) },
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Run.Stop,
                    tooltip = log.stopAction,
                    enabled = running,
                    onClick = { commandBus.send(AppCommand.StopServer) },
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.Restart,
                    tooltip = log.restartAction,
                    enabled = running,
                    onClick = { commandBus.send(AppCommand.RestartServer) },
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.Copy,
                    tooltip = log.endpoint,
                    onClick = { commandBus.send(AppCommand.CopyServerEndpoint) },
                )
                ToolbarIconButton(
                    key = AllIconsKeys.General.Delete,
                    tooltip = log.clearAction,
                    onClick = { repository.clear() },
                )
                ToolbarIconButton(
                    key = AllIconsKeys.Actions.Find,
                    tooltip = log.findTooltip,
                    shortcut = shortcuts.search,
                    onClick = { model.toggleFind() },
                )
                ToolWindowOptionsButton(
                    options = options,
                    viewMode = state.viewMode,
                    moveTo = state.moveTo,
                    onViewMode = { model.onLayoutEvent(ToolWindowLayoutAction.SetViewMode(it)) },
                    onMoveTo = { model.onLayoutEvent(ToolWindowLayoutAction.SetMoveTo(it)) },
                    onResize = model::resize,
                )
                ToolbarIconButton(
                    key = AllIconsKeys.General.HideToolWindow,
                    tooltip = log.hide,
                    onClick = onHide,
                )
            },
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = log.empty,
                        style = JewelTheme.typography.small,
                        color = JewelTheme.globalColors.text.info,
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(entries) { index, entry ->
                        val lineRanges = remember(index, matches) {
                            matches.filter { it.first == index }.map { it.second }
                        }
                        val focused = matches.getOrNull(state.currentMatch)
                            ?.takeIf { it.first == index }?.second
                        LogLine(entry = entry, ranges = lineRanges, focusedRange = focused)
                    }
                }
            }

            if (state.findBarOpen) {
                FindBar(
                    model = model,
                    state = state,
                    focusRequester = findFocusRequester,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
    }
}

/** The floating IntelliJ find bar: the shared search field plus a match counter and prev / next / close. */
@Composable
private fun FindBar(
    model: ServerLogWindowModel,
    state: ServerLogWindowState,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val log = blazeStrings.serverLog
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(JewelTheme.globalColors.panelBackground)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolWindowSearchField(
            query = state.findQuery,
            placeholder = log.findPlaceholder,
            clearDescription = log.clearSearch,
            onQueryChange = model::setFindQuery,
            fieldWidth = BlazeLayout.SearchFieldWidth,
            focusRequester = focusRequester,
        )
        Text(
            text = when {
                state.findQuery.isBlank() -> ""
                state.matchCount == 0 -> log.noMatches
                else -> log.matchCount(state.currentMatch + 1, state.matchCount)
            },
            style = JewelTheme.typography.small,
            color = if (state.findQuery.isNotBlank() && state.matchCount == 0) BlazeColors.error else JewelTheme.globalColors.text.info,
            modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 6.dp),
        )
        ToolbarIconButton(
            key = AllIconsKeys.Actions.FindAndShowPrevMatches,
            tooltip = log.previousMatch,
            enabled = state.matchCount > 0,
            onClick = { model.previousMatch() },
        )
        ToolbarIconButton(
            key = AllIconsKeys.Actions.FindAndShowNextMatches,
            tooltip = log.nextMatch,
            enabled = state.matchCount > 0,
            onClick = { model.nextMatch() },
        )
        ToolbarIconButton(
            key = AllIconsKeys.General.Delete,
            tooltip = log.closeFind,
            onClick = { model.closeFind() },
        )
    }
}

/** One buffered line in the console face, level-tinted, with every find match and the focused one highlighted. */
@Composable
private fun LogLine(entry: LogEntry, ranges: List<IntRange>, focusedRange: IntRange?) {
    val text = entry.render()
    val defaultColor = JewelTheme.globalColors.text.normal
    val levelColor = when (entry.level.uppercase()) {
        "ERROR" -> BlazeColors.error
        "WARN", "WARNING" -> BlazeColors.warning
        else -> defaultColor
    }
    // The current-match span rides on top of the all-matches span, so the focused hit reads in full
    // accent while every other occurrence keeps its translucent wash.
    val accent = BlazeColors.accent
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = levelColor)) { append(text) }
        ranges.forEach { addStyle(SpanStyle(background = accent.copy(alpha = 0.30f)), it.first, it.last + 1) }
        focusedRange?.let { addStyle(SpanStyle(background = accent), it.first, it.last + 1) }
    }
    Text(
        text = annotated,
        style = JewelTheme.typography.consoleTextStyle,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/** Case-insensitive, non-overlapping occurrences of [query] in [text], as index ranges into [text]. */
private fun matchRanges(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var index = text.indexOf(query, 0, ignoreCase = true)
    while (index >= 0) {
        out += index until index + query.length
        index = text.indexOf(query, index + query.length, ignoreCase = true)
    }
    return out
}
