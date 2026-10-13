package org.island.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.island.theme.ui.*
import org.island.ui.text.LocalIslandUiStrings
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Search Everywhere: the generic window-level popup an app opens from its title bar, menu or search
 * chord. It floats near the top-center on an island surface (radius, 1dp rim, small shadow) inside a
 * full-window, focusable [Popup] — the same mechanism the island menus use, chosen so the field can
 * never lose pointer or keyboard input the way a plain overlay could.
 *
 * All labels come from [LocalIslandUiStrings]; every result row's leading widget comes from
 * [leading], so apps draw whatever identity their families carry (a status dot for entities, a gem
 * for actions, the provider icon by default). The popup reads all its state from the shared
 * [IslandSearchController]: typing filters live (no debounce) across the tabs, matched characters are
 * highlighted, and results activate through their provider.
 */
@Composable
fun IslandSearchEverywherePopup(
    controller: IslandSearchController,
    leading: @Composable (SearchHit) -> Unit = { IslandSearchDefaultLeading(it) },
) {
    if (!controller.isOpen) return

    Popup(
        onDismissRequest = controller::close,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = false),
        popupPositionProvider = FullWindowSearchPositionProvider,
        // Navigation keys are handled on the *preview* pass (top-down, before the focused text field)
        // so Enter/Tab/arrows act on the popup rather than being swallowed by the field (a focused
        // single-line field consumes Enter as its IME action, and would otherwise never let it bubble up).
        // Unhandled keys return false and fall through, so ordinary typing into the field is untouched.
        onPreviewKeyEvent = { handleSearchKey(it, controller) },
    ) {
        // A transparent scrim fills the window so a click anywhere but the island dismisses it, while the
        // island itself sits near the top-center — the layout a full-window popup cannot express with a
        // simple alignment, hence the whole-window content + explicit placement.
        Box(modifier = Modifier.fillMaxSize().clickable(onClick = controller::close)) {
            IslandSearchIsland(
                controller = controller,
                leading = leading,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .semantics { testTag = "search_everywhere_popup" },
            )
        }
    }
}

/** The stock row leading: the hit's [IconKey] when it carries one, a settings gear otherwise. */
@Composable
fun IslandSearchDefaultLeading(hit: SearchHit) {
    Icon(
        key = hit.icon as? IconKey ?: AllIconsKeys.General.Settings,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
    )
}

/** Mirrors the island menus' full-window provider: the popup content fills and is measured against the window. */
private object FullWindowSearchPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

@OptIn(ExperimentalJewelApi::class)
@Composable
fun IslandSearchIsland(
    controller: IslandSearchController,
    modifier: Modifier = Modifier,
    leading: @Composable (SearchHit) -> Unit = { IslandSearchDefaultLeading(it) },
) {
    val strings = LocalIslandUiStrings.current
    val shape = RoundedCornerShape(IslandLayout.IslandRadius)

    Column(
        modifier = modifier
            .widthIn(min = 560.dp, max = 720.dp)
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(IslandChromeColors.island, shape)
            .border(1.dp, IslandChromeColors.islandBorder, shape)
            .padding(vertical = 8.dp),
    ) {
        val queryField = remember { mutableStateOf(TextFieldValue(controller.query)) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

        // Field ↔ controller: the field owns the caret, the controller owns the query the providers rank.
        TextField(
            value = queryField.value,
            onValueChange = { queryField.value = it; controller.updateQuery(it.text) },
            style = islandSearchFieldStyle(),
            placeholder = {
                Text(
                    text = strings.searchEverywherePlaceholder(),
                    style = IslandTypography.fieldPlaceholder,
                    color = JewelTheme.globalColors.text.info,
                    maxLines = 1,
                )
            },
            leadingIcon = {
                Icon(key = AllIconsKeys.Actions.Find, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .focusRequester(focusRequester),
        )

        SearchTabs(
            controller = controller,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        )

        // Providers read the app's live snapshot state inside `candidates`, so remembering on the
        // query and tab is enough — a data change recomputes this through the snapshot read itself.
        val hits = remember(controller.query, controller.tabId) {
            controller.computeHits(strings)
        }
        // Publish the ranked hits so the popup's single keyboard handler can walk and activate them.
        LaunchedEffect(hits) { controller.publishHits(hits) }

        val listState = rememberLazyListState()
        val resultRowIndex = remember(hits) { buildResultRowIndices(hits, controller.tabId) }
        LaunchedEffect(controller.highlighted, hits.size) {
            if (hits.isNotEmpty()) {
                val row = resultRowIndex[controller.highlighted.coerceIn(0, hits.lastIndex)]
                val last = listState.layoutInfo.totalItemsCount - 1
                if (last >= 0) listState.scrollToItem(row.coerceIn(0, last))
            }
        }

        val allTab = controller.tabId == IslandSearchController.ALL_TAB_ID
        when {
            controller.query.isBlank() && allTab ->
                EmptyHint(strings.searchTypeToSearch())

            hits.isEmpty() && controller.query.isNotBlank() ->
                EmptyHint(strings.searchNothingFound())

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(320.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                var lastGroup: String? = null
                hits.forEachIndexed { index, hit ->
                    if (allTab && hit.groupKey != lastGroup) {
                        lastGroup = hit.groupKey
                        controller.provider(hit.groupKey)?.let { provider ->
                            item(key = "header_${provider.id}") { SectionHeader(provider.sectionTitle(strings)) }
                        }
                    }
                    item(key = "${hit.groupKey}_${hit.id}") {
                        SearchResultRow(
                            hit = hit,
                            leading = leading,
                            highlighted = index == controller.highlighted,
                            onClick = { controller.activate(hit) },
                            onHover = { controller.highlight(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTabs(controller: IslandSearchController, modifier: Modifier = Modifier) {
    val strings = LocalIslandUiStrings.current
    val tabs = buildList {
        add(IslandSearchController.ALL_TAB_ID to strings.searchTabAll())
        controller.providers.forEach { add(it.id to it.tabLabel(strings)) }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEach { (tabId, label) ->
            val isSelected = tabId == controller.tabId
            val shape = RoundedCornerShape(6.dp)
            Text(
                text = label,
                style = JewelTheme.typography.small,
                color = if (isSelected) IslandSemanticColors.accent else JewelTheme.globalColors.text.info,
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSelected) IslandSemanticColors.hover else Color.Transparent, shape)
                    .clickable { controller.selectTab(tabId) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .semantics { selected = isSelected; testTag = "search_tab_${tabId.lowercase()}" },
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHit,
    leading: @Composable (SearchHit) -> Unit,
    highlighted: Boolean,
    onClick: () -> Unit,
    onHover: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = highlighted || hovered
    LaunchedEffect(hovered) { if (hovered) onHover() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) IslandSemanticColors.hover else Color.Transparent)
            .clickable(onClick = onClick)
            .hoverable(interaction)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { testTag = "search_result_${hit.id}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading(hit)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightPrimary(hit.primary, hit.matches),
                style = JewelTheme.typography.regular,
                color = JewelTheme.globalColors.text.normal,
                maxLines = 1,
            )
            val secondary = hit.secondary
            if (!secondary.isNullOrEmpty()) {
                Text(
                    text = secondary,
                    style = JewelTheme.typography.small,
                    color = JewelTheme.globalColors.text.info,
                    maxLines = 1,
                )
            }
        }
        val shortcut = hit.shortcut
        if (!shortcut.isNullOrEmpty()) {
            Text(
                text = shortcut,
                style = JewelTheme.typography.small,
                color = JewelTheme.globalColors.text.info,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = JewelTheme.typography.small,
        color = JewelTheme.globalColors.text.info,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = JewelTheme.typography.small, color = JewelTheme.globalColors.text.info)
    }
}

/** Wraps the matched [ranges] of [text] in an accent-tinted span; the rest is the plain text color. */
private fun highlightPrimary(text: String, ranges: List<MatchRange>): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        for (range in ranges.sortedBy { it.start }) {
            val start = range.start.coerceIn(0, text.length)
            val end = range.end.coerceIn(start, text.length)
            if (start > cursor) append(text.substring(cursor, start))
            withStyle(SpanStyle(background = IslandHighlightBackground)) {
                append(text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

private val IslandHighlightBackground = Color(0x3D548AF7) // ~24% accent wash over matched characters.

/** Maps each result ordinal to its row index in the LazyColumn (results are offset by leading headers). */
private fun buildResultRowIndices(hits: List<SearchHit>, tabId: String): IntArray {
    if (tabId != IslandSearchController.ALL_TAB_ID) return IntArray(hits.size) { it }
    val indices = IntArray(hits.size)
    var row = 0
    var lastGroup: String? = null
    hits.forEachIndexed { ordinal, hit ->
        if (hit.groupKey != lastGroup) {
            lastGroup = hit.groupKey
            row += 1 // section header occupies a row before the first hit of each group
        }
        indices[ordinal] = row
        row += 1
    }
    return indices
}

/** The popup's keyboard model: navigate the list, switch tabs, activate the selection and dismiss. */
private fun handleSearchKey(event: KeyEvent, controller: IslandSearchController): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val primary = event.isCtrlPressed || event.isMetaPressed
    val k = event.key
    return when {
        k == Key.Escape -> {
            controller.close(); true
        }

        k == Key.Tab && event.isShiftPressed -> {
            controller.cycleTab(-1); true
        }

        k == Key.Tab -> {
            controller.cycleTab(1); true
        }

        primary && k == Key.DirectionDown -> {
            controller.cycleTab(1); true
        }

        primary && k == Key.DirectionUp -> {
            controller.cycleTab(-1); true
        }

        k == Key.DirectionDown -> {
            controller.moveDown(); true
        }

        k == Key.DirectionUp -> {
            controller.moveUp(); true
        }

        k == Key.PageDown -> {
            controller.page(PAGE_STEP); true
        }

        k == Key.PageUp -> {
            controller.page(-PAGE_STEP); true
        }

        k == Key.Enter || k == Key.NumPadEnter -> {
            controller.activateSelected(); true
        }

        else -> false
    }
}

/** How many rows a Page key jumps; matches the popup's roughly eight visible results. */
private const val PAGE_STEP = 8
