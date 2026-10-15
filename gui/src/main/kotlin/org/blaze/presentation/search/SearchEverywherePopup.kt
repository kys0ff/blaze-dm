package org.blaze.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BlazeIslandStrings
import org.blaze.presentation.screens.downloads.components.indicatorColor
import org.island.theme.ui.IslandSemanticColors
import org.island.ui.search.IslandSearchDefaultLeading
import org.island.ui.search.IslandSearchEverywherePopup
import org.island.ui.search.IslandSearchIsland
import org.island.ui.search.SearchHit
import org.island.ui.text.LocalIslandUiStrings

/**
 * Blaze's Search Everywhere entry point: the generic island popup wired to this app's three result
 * families. Renders in Blaze's strings (via [BlazeIslandStrings] over the live [org.blaze.i18n.BlazeStrings])
 * and draws download rows with the same status dot the list table uses; everything else keeps the
 * island's stock leading icon.
 *
 * Rendered once, at the window root (see BlazeWindow), reading all its state from the shared
 * [SearchController].
 */
@Composable
fun SearchEverywherePopup(controller: SearchController) {
    val island = controller.island
    WithBlazeSearchStrings {
        IslandSearchEverywherePopup(
            controller = island,
            leading = { hit -> BlazeSearchLeading(hit) },
        )
    }
}

/**
 * The bare search island — the surface the full-window [Popup] hosts — without the scrim or the
 * window-level placement. Screenshot fixtures (and any app that wants the card embedded rather than
 * popped) render this; production chrome goes through [SearchEverywherePopup].
 */
@Composable
internal fun SearchIsland(controller: SearchController, modifier: Modifier = Modifier) {
    WithBlazeSearchStrings {
        IslandSearchIsland(
            controller = controller.island,
            modifier = modifier,
            leading = { hit -> BlazeSearchLeading(hit) },
        )
    }
}

@Composable
private fun WithBlazeSearchStrings(content: @Composable () -> Unit) {
    val blaze = blazeStrings
    val strings = remember(blaze) { BlazeIslandStrings(blaze) }
    CompositionLocalProvider(LocalIslandUiStrings provides strings, content = content)
}

/** The row's leading widget: download results draw their live status dot, everything else the icon. */
@Composable
private fun BlazeSearchLeading(hit: SearchHit) {
    if (hit.groupKey == SearchKind.Download.groupId) {
        // The download family's identity is its state — the same indicator color the table rows
        // draw, not a static glyph.
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(
                    (hit.icon as? DownloadState)?.indicatorColor() ?: IslandSemanticColors.accent,
                ),
        )
    } else {
        IslandSearchDefaultLeading(hit)
    }
}
