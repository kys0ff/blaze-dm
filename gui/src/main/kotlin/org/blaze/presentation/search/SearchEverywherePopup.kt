package org.blaze.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.screens.downloads.components.indicatorColor
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.theme.BlazeIslandColors
import org.blaze.presentation.theme.BlazeTypography
import org.blaze.presentation.theme.blazeSearchFieldStyle
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Search Everywhere: the one window-level popup the title-bar icon, the hamburger item and Ctrl/Cmd+K
 * all open. It floats near the top-center on an island surface (radius, 1dp rim, small shadow) inside a
 * full-window, focusable [Popup] — the very mechanism [org.blaze.presentation.components.BlazeMenu] uses,
 * chosen so the field can never lose pointer or keyboard input the way a plain overlay could.
 *
 * It is rendered once, at the window root (see BlazeWindow), and reads all its state from the shared
 * [SearchController]; typing filters live (no debounce) across Downloads / Actions / Settings, matched
 * characters are highlighted, and results activate their target. The tab that opens by default depends on
 * context: opening from inside Settings starts on the Settings tab, everywhere else on All.
 */
@Composable
fun SearchEverywherePopup(controller: SearchController) {
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
            SearchIsland(
                controller = controller,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .semantics { testTag = "search_everywhere_popup" },
            )
        }
    }
}

/** Mirrors BlazeMenu's full-window provider: the popup content fills and is measured against the window. */
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
internal fun SearchIsland(controller: SearchController, modifier: Modifier = Modifier) {
    val strings = blazeStrings
    val s = strings.search
    val shape = RoundedCornerShape(BlazeLayout.IslandRadius)

    Column(
        modifier = modifier
            .widthIn(min = 560.dp, max = 720.dp)
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(BlazeIslandColors.island, shape)
            .border(1.dp, BlazeIslandColors.islandBorder, shape)
            .padding(vertical = 8.dp),
    ) {
        val queryField = remember { mutableStateOf(TextFieldValue(controller.query)) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

        // Field ↔ controller: the field owns the caret, the controller owns the query the sources rank.
        TextField(
            value = queryField.value,
            onValueChange = { queryField.value = it; controller.updateQuery(it.text) },
            style = blazeSearchFieldStyle(),
            placeholder = {
                Text(
                    text = s.placeholder,
                    style = BlazeTypography.fieldPlaceholder,
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
            selectedTab = controller.tab,
            onSelect = controller::selectTab,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        )

        val hits = remember(controller.query, controller.tab, controller.downloads) {
            orderForTab(
                SearchSources.hits(controller.tab, controller.query, controller.downloads, strings),
                controller.tab,
            )
        }
        // Publish the ranked hits so the popup's single keyboard handler can walk and activate them.
        LaunchedEffect(hits) { controller.publishHits(hits) }

        val listState = rememberLazyListState()
        val resultRowIndex = remember(hits) { buildResultRowIndices(hits, controller.tab) }
        LaunchedEffect(controller.highlighted, hits.size) {
            if (hits.isNotEmpty()) {
                val row = resultRowIndex[controller.highlighted.coerceIn(0, hits.lastIndex)]
                val last = listState.layoutInfo.totalItemsCount - 1
                if (last >= 0) listState.scrollToItem(row.coerceIn(0, last))
            }
        }

        when {
            controller.query.isBlank() && controller.tab == SearchTab.All ->
                EmptyHint(s.typeToSearch)
            hits.isEmpty() && controller.query.isNotBlank() ->
                EmptyHint(s.nothingFound)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(320.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                var lastKind: SearchKind? = null
                hits.forEachIndexed { index, hit ->
                    if (controller.tab == SearchTab.All && hit.kind != lastKind) {
                        lastKind = hit.kind
                        item(key = "header_${hit.kind}") { SectionHeader(sectionTitle(hit.kind, s)) }
                    }
                    item(key = "${hit.kind}_${hit.id}") {
                        SearchResultRow(
                            hit = hit,
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
private fun SearchTabs(selectedTab: SearchTab, onSelect: (SearchTab) -> Unit, modifier: Modifier = Modifier) {
    val s = blazeStrings.search
    val tabs = listOf(
        SearchTab.All to s.tabAll,
        SearchTab.Downloads to s.tabDownloads,
        SearchTab.Actions to s.tabActions,
        SearchTab.Settings to s.tabSettings,
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEach { (tab, label) ->
            val isSelected = tab == selectedTab
            val shape = RoundedCornerShape(6.dp)
            Text(
                text = label,
                style = JewelTheme.typography.small,
                color = if (isSelected) BlazeColors.accent else JewelTheme.globalColors.text.info,
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSelected) BlazeColors.hover else Color.Transparent, shape)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .semantics { selected = isSelected; testTag = "search_tab_${tab.name.lowercase()}" },
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHit,
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
            .background(if (active) BlazeColors.hover else Color.Transparent)
            .clickable(onClick = onClick)
            .hoverable(interaction)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { testTag = "search_result_${hit.id}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ResultLeading(hit)
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
private fun ResultLeading(hit: SearchHit) {
    when (hit.kind) {
        SearchKind.Download -> Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background((hit.icon as? DownloadState)?.indicatorColor() ?: BlazeColors.accent),
        )
        else -> Icon(
            key = hit.icon as? IconKey ?: AllIconsKeys.General.Settings,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
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
            withStyle(SpanStyle(background = BlazeHighlightBackground)) {
                append(text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

private val BlazeHighlightBackground = Color(0x3D548AF7) // ~24% accent wash over matched characters.

private fun sectionTitle(kind: SearchKind, s: BlazeStrings.Search): String = when (kind) {
    SearchKind.Download -> s.sectionDownloads
    SearchKind.Action -> s.sectionActions
    SearchKind.Setting -> s.sectionSettings
}

/**
 * Orders the ranked hits the way they are shown: the All tab groups by kind (Downloads, Actions,
 * Settings) keeping the ranked order within a group, so Up/Down follows the visible list. Every other
 * tab is already single-kind and stays in ranked order.
 */
private fun orderForTab(hits: List<SearchHit>, tab: SearchTab): List<SearchHit> {
    if (tab != SearchTab.All) return hits
    return buildList {
        addAll(hits.filter { it.kind == SearchKind.Download })
        addAll(hits.filter { it.kind == SearchKind.Action })
        addAll(hits.filter { it.kind == SearchKind.Setting })
    }
}

/** Maps each result ordinal to its row index in the LazyColumn (results are offset by leading headers). */
private fun buildResultRowIndices(hits: List<SearchHit>, tab: SearchTab): IntArray {
    if (tab != SearchTab.All) return IntArray(hits.size) { it }
    val indices = IntArray(hits.size)
    var row = 0
    var lastKind: SearchKind? = null
    hits.forEachIndexed { ordinal, hit ->
        if (hit.kind != lastKind) {
            lastKind = hit.kind
            row += 1 // section header occupies a row before the first hit of each kind
        }
        indices[ordinal] = row
        row += 1
    }
    return indices
}

/** The popup's keyboard model: navigate the list, switch tabs, activate the selection and dismiss. */
private fun handleSearchKey(event: KeyEvent, controller: SearchController): Boolean {
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
    val primary = event.isCtrlPressed || event.isMetaPressed
    val k = event.key
    return when {
        k == Key.Escape -> { controller.close(); true }
        k == Key.Tab && event.isShiftPressed -> { controller.cycleTab(-1); true }
        k == Key.Tab -> { controller.cycleTab(1); true }
        primary && k == Key.DirectionDown -> { controller.cycleTab(1); true }
        primary && k == Key.DirectionUp -> { controller.cycleTab(-1); true }
        k == Key.DirectionDown -> { controller.moveDown(); true }
        k == Key.DirectionUp -> { controller.moveUp(); true }
        k == Key.PageDown -> { controller.page(PAGE_STEP); true }
        k == Key.PageUp -> { controller.page(-PAGE_STEP); true }
        k == Key.Enter || k == Key.NumPadEnter -> { controller.activateSelected(); true }
        else -> false
    }
}

/** How many rows a Page key jumps; matches the popup's roughly eight visible results. */
private const val PAGE_STEP = 8
