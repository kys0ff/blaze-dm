package org.blaze.presentation.components.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.Island
import org.blaze.presentation.components.LocalWindowActive
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle
import kotlin.math.abs

/**
 * Unified Tool Window Workspace host layout that handles multi-panel docking and floating windows.
 *
 * The five view modes split along two axes, and the host routes each pane by them:
 *
 * - **Docked** ([ToolWindowViewMode.isDocked]) panes reserve a permanent share of the frame through the
 *   outer split. They are routed into the three dock regions (Left, Right, Bottom) around the central
 *   content; several panes in one region share it through an inner split.
 * - **Overlay** ([ToolWindowViewMode.overlaysContent]) panes cover the content instead of reserving a
 *   share of it, so the list keeps its full width. A pane unpinned from a dock edge is drawn flush
 *   against that edge at the size the user dragged it to
 *   ([EdgeDockedOverlay]); a pane undocked or floated is a free, draggable window
 *   ([FloatingToolWindow]) in the same layer.
 * - **Auto-hiding** ([ToolWindowViewMode.autoHidesWhenUnfocused]) panes fold away the moment attention
 *   leaves them: a press that lands outside the pane, or the frame losing focus. That is the one
 *   behavior an unpinned tool window exists for, and it is what a pinned pane refuses to do.
 */
@Composable
fun ToolWindowLayoutHost(
    mainContent: @Composable () -> Unit,
    panels: List<ToolWindowSpec>,
    modifier: Modifier = Modifier,
) {
    val activePanels = panels.filter { it.visible }
    val dockedPanels = activePanels.filter { it.viewMode.isDocked }
    val overlayPanels = activePanels.filter { it.viewMode.overlaysContent }

    val gapDivider = DividerStyle(
        color = Color.Transparent,
        metrics = DividerMetrics(thickness = BlazeLayout.IslandGap, startIndent = 0.dp),
    )

    // The panes that only live while they hold attention, and the rectangles they were last drawn in -
    // both read by the layer's hit test, which is what decides whether a press left one of them.
    val autoHidingPanels = overlayPanels.filter { it.viewMode.autoHidesWhenUnfocused }
    val collapsing by rememberUpdatedState(autoHidingPanels)
    val overlayBounds = remember { mutableStateMapOf<String, Rect>() }
    var raisedOverlayId by remember { mutableStateOf<String?>(null) }
    val windowActive = LocalWindowActive.current

    // Leaving the application is leaving the pane: the IDE drops an unpinned tool window when the frame
    // is deactivated. Only a *change* of focus does it - a pane restored in that mode while the frame
    // was still coming up would otherwise be closed by its own first frame.
    var wasActive by remember { mutableStateOf(windowActive) }
    LaunchedEffect(windowActive) {
        if (wasActive && !windowActive) collapsing.forEach { it.onClose() }
        wasActive = windowActive
    }

    // A pane that left the layer must not leave its rectangle behind, or a gone-pane-shaped hole in the
    // hit test would keep swallowing clicks meant to collapse its replacement.
    val overlayIds = overlayPanels.map { it.id }
    LaunchedEffect(overlayIds) {
        overlayBounds.keys.toList()
            .filterNot { it in overlayIds }
            .forEach { overlayBounds.remove(it) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        DockedWorkspace(mainContent, dockedPanels, gapDivider)

        if (overlayPanels.isNotEmpty()) {
            // The pane last touched is drawn above its siblings, so overlapping windows stack the way
            // the user reached them rather than the order the screen model happens to list them in.
            OverlayLayer(
                panels = overlayPanels.sortedBy { if (it.id == raisedOverlayId) 1 else 0 },
                bounds = overlayBounds,
                autoHidingPanels = autoHidingPanels,
                onRaised = { raisedOverlayId = it },
            )
        }
    }
}

/**
 * The panes docked to one region, in the order they stack: the region's corner first. Internal (rather
 * than private) so the categorization the layout relies on can be asserted directly.
 */
internal fun List<ToolWindowSpec>.regionFor(
    start: ToolWindowMoveTo,
    end: ToolWindowMoveTo,
): List<ToolWindowSpec> = filter { it.moveTo == start || it.moveTo == end }
    .sortedBy { if (it.moveTo == start) 0 else 1 }

/**
 * The central content with one outer split per docked region around it. A region on the left or the
 * right divides the frame with a vertical divider, the bottom region with a horizontal one; the
 * panes *inside* a region divide the other way.
 */
@Composable
private fun DockedWorkspace(
    mainContent: @Composable () -> Unit,
    dockedPanels: List<ToolWindowSpec>,
    gapDivider: DividerStyle,
) {
    val leftPanels = dockedPanels.regionFor(ToolWindowMoveTo.LEFT_TOP, ToolWindowMoveTo.LEFT_BOTTOM)
    val rightPanels = dockedPanels.regionFor(ToolWindowMoveTo.RIGHT_TOP, ToolWindowMoveTo.RIGHT_BOTTOM)
    val bottomPanels = dockedPanels.regionFor(ToolWindowMoveTo.BOTTOM_LEFT, ToolWindowMoveTo.BOTTOM_RIGHT)

    var workspace: @Composable () -> Unit = mainContent

    if (leftPanels.isNotEmpty()) {
        val content = workspace
        workspace = {
            OuterRegionSplit(
                region = leftPanels,
                content = content,
                paneFirst = true,
                outerHorizontal = true,
                paneMin = BlazeLayout.DetailsPaneMinWidth,
                contentMin = BlazeLayout.ListPaneMinWidth,
                gapDivider = gapDivider,
            )
        }
    }

    if (rightPanels.isNotEmpty()) {
        val content = workspace
        workspace = {
            OuterRegionSplit(
                region = rightPanels,
                content = content,
                paneFirst = false,
                outerHorizontal = true,
                paneMin = BlazeLayout.DetailsPaneMinWidth,
                contentMin = BlazeLayout.ListPaneMinWidth,
                gapDivider = gapDivider,
            )
        }
    }

    if (bottomPanels.isNotEmpty()) {
        val content = workspace
        workspace = {
            OuterRegionSplit(
                region = bottomPanels,
                content = content,
                paneFirst = false,
                outerHorizontal = false,
                paneMin = BlazeLayout.BottomPaneMinHeight,
                contentMin = BlazeLayout.ContentPaneMinHeight,
                gapDivider = gapDivider,
            )
        }
    }

    workspace()
}

/**
 * One dock region and the split that separates it from the content.
 *
 * The region's panes share one size: [ToolWindowSpec.splitFraction] is the *content*'s share of this
 * split, so the region as a whole holds `1 - splitFraction`, and every pane in it reports that same
 * value back when the divider is dragged. How the region then divides *between* its panes is each
 * pane's own `innerSplitFraction` (see [DockRegion]).
 */
@Composable
private fun OuterRegionSplit(
    region: List<ToolWindowSpec>,
    content: @Composable () -> Unit,
    paneFirst: Boolean,
    outerHorizontal: Boolean,
    paneMin: Dp,
    contentMin: Dp,
    gapDivider: DividerStyle,
) {
    val primary = region.first()
    val contentFraction = primary.splitFraction
    val pane: @Composable () -> Unit = {
        DockRegion(region, horizontalInner = !outerHorizontal, gapDivider = gapDivider)
    }
    // A divider is remembered as the first pane's share, so the side the pane sits on decides both the
    // fraction handed to the layout and the way a drag is translated back into the content's share.
    val reportContentFraction: (Float) -> Unit = { firstShare ->
        val contentShare = if (paneFirst) 1f - firstShare else firstShare
        region.forEach { it.onSplitFractionChange(contentShare) }
    }

    if (paneFirst) {
        SyncedSplit(
            splitKey = "dock-left-${primary.id}",
            fraction = 1f - contentFraction,
            onFractionChange = reportContentFraction,
            horizontal = outerHorizontal,
            first = pane,
            second = content,
            firstMin = paneMin,
            secondMin = contentMin,
            gapDivider = gapDivider,
        )
    } else {
        SyncedSplit(
            splitKey = "dock-region-${primary.id}",
            fraction = contentFraction,
            onFractionChange = reportContentFraction,
            horizontal = outerHorizontal,
            first = content,
            second = pane,
            firstMin = contentMin,
            secondMin = paneMin,
            gapDivider = gapDivider,
        )
    }
}

/**
 * The panes docked to one region, each in its own island. A single pane fills the region; two or more
 * divide it with the region's own split, recursively, so a third pane lands in the remainder instead
 * of being silently dropped the way a fixed two-pane region would have.
 */
@Composable
private fun DockRegion(
    region: List<ToolWindowSpec>,
    horizontalInner: Boolean,
    gapDivider: DividerStyle,
) {
    when {
        region.isEmpty() -> Box(modifier = Modifier.fillMaxSize())

        region.size == 1 -> PaneIsland(region.first())

        else -> {
            val first = region.first()
            val rest = region.subList(1, region.size)
            val paneMin = if (horizontalInner) BlazeLayout.StackedPaneMinWidth else BlazeLayout.StackedPaneMinHeight
            SyncedSplit(
                splitKey = "inner-${first.id}",
                fraction = first.innerSplitFraction,
                onFractionChange = { firstShare ->
                    first.onInnerSplitFractionChange(firstShare)
                    // Everything below the first pane divides what is left of the region, so each of
                    // them stores the complement of the share the pane above it was given.
                    rest.forEach { it.onInnerSplitFractionChange(1f - firstShare) }
                },
                horizontal = horizontalInner,
                first = { PaneIsland(first) },
                second = { DockRegion(rest, horizontalInner, gapDivider) },
                firstMin = paneMin,
                secondMin = paneMin,
                gapDivider = gapDivider,
            )
        }
    }
}

/** A docked pane in the island design language: the pane's own content, rimmed and filling its share. */
@Composable
private fun PaneIsland(spec: ToolWindowSpec) {
    key(spec.id) {
        Island(modifier = Modifier.fillMaxSize()) {
            spec.content()
        }
    }
}

/**
 * A split whose divider is driven by [fraction] (the first pane's share) and reports a drag back through
 * [onFractionChange].
 *
 * Jewel coerces the divider to the two panes' minimum sizes *during measurement* and writes the value
 * it settled on back into its state, so a plain two-way binding loops: the layout's answer differs from
 * the fraction just pushed in, is reported to the model, and the model pushes it back. The guard here
 * keeps that loop closed - a position that already matches what was asked for is never reported, and a
 * fraction that already matches the layout is never re-applied - which is what used to make a dragged
 * divider fight and jitter against its own persisted value.
 */
@Composable
private fun SyncedSplit(
    splitKey: String,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    horizontal: Boolean,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    firstMin: Dp,
    secondMin: Dp,
    gapDivider: DividerStyle,
) {
    val expectedFraction by rememberUpdatedState(fraction)
    val reportFraction by rememberUpdatedState(onFractionChange)
    val state = remember(splitKey) { SplitLayoutState(fraction) }

    LaunchedEffect(splitKey, fraction, state) {
        if (abs(state.dividerPosition - fraction) > FRACTION_EPSILON) {
            state.dividerPosition = fraction
        }
    }
    LaunchedEffect(splitKey, state) {
        snapshotFlow { state.dividerPosition }
            .distinctUntilChanged()
            .collect { position ->
                if (abs(position - expectedFraction) > FRACTION_EPSILON) reportFraction(position)
            }
    }

    if (horizontal) {
        HorizontalSplitLayout(
            first = first,
            second = second,
            modifier = Modifier.fillMaxSize(),
            dividerStyle = gapDivider,
            draggableWidth = BlazeLayout.SplitDragWidth,
            firstPaneMinWidth = firstMin,
            secondPaneMinWidth = secondMin,
            state = state,
        )
    } else {
        VerticalSplitLayout(
            first = first,
            second = second,
            modifier = Modifier.fillMaxSize(),
            dividerStyle = gapDivider,
            draggableWidth = BlazeLayout.SplitDragWidth,
            firstPaneMinWidth = firstMin,
            secondPaneMinWidth = secondMin,
            state = state,
        )
    }
}

/**
 * The layer every pane that covers the content is drawn in, and the hit test that decides when one of
 * them is left.
 *
 * The catcher and the panes share one coordinate space - the press position the layer reports and the
 * bounds the panes report are both relative to this same box - which is the only reason the
 * inside/outside question can be answered honestly. The gestures run in the Main pass and never
 * consume, so the click that collapses an unpinned pane still lands on whatever it hit underneath:
 * selecting another row is how an unpinned Details pane is asked to come back.
 */
@Composable
private fun OverlayLayer(
    panels: List<ToolWindowSpec>,
    bounds: MutableMap<String, Rect>,
    autoHidingPanels: List<ToolWindowSpec>,
    onRaised: (String) -> Unit,
) {
    // The gesture loop reads the latest panels through these, so it never restarts mid-press (a restart
    // used to swallow the very press that was meant to collapse the pane).
    val stacked by rememberUpdatedState(panels)
    val collapsing by rememberUpdatedState(autoHidingPanels)
    val raise by rememberUpdatedState(onRaised)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type != PointerEventType.Release) continue
                        val press = event.changes.firstOrNull()?.position ?: continue
                        // The topmost pane the pointer actually landed on wins, exactly as it did on screen.
                        val hit = stacked.lastOrNull { bounds[it.id]?.contains(press) == true }
                        if (hit != null) {
                            raise(hit.id)
                        } else {
                            collapsing.forEach { it.onClose() }
                        }
                    }
                }
            },
    ) {
        panels.forEach { spec ->
            key(spec.id) {
                if (spec.viewMode.isFloatingWindow) {
                    FloatingToolWindow(
                        spec = spec,
                        modifier = Modifier.fillMaxSize(),
                        onBounds = { rect -> bounds[spec.id] = rect },
                    )
                } else {
                    // Both kinds report their rectangle in this layer's coordinates: the floating window
                    // fills the layer before offsetting itself, and the edge pane is a direct child here.
                    EdgeDockedOverlay(
                        spec = spec,
                        onBounds = { rect -> bounds[spec.id] = rect },
                    )
                }
            }
        }
    }
}

/**
 * A tool window unpinned from its dock edge: it keeps the edge and the size the user dragged it to, but
 * it is drawn *over* the content instead of taking a share of it, and it carries the shadow that says
 * so. The corner half of [ToolWindowMoveTo] is meaningless for a pane that spans its edge, so only the
 * dominant side is honored - which is exactly what the IDE does with an unpinned window.
 */
@Composable
private fun BoxScope.EdgeDockedOverlay(
    spec: ToolWindowSpec,
    onBounds: (Rect) -> Unit,
) {
    // The pane's share of the frame is the complement of the content's, floored so the pane's own
    // header still fits and capped so the content it covers never disappears whole.
    val paneShare = (1f - spec.splitFraction).coerceIn(0.2f, 0.9f)
    val dockedToBottom = spec.moveTo.isBottom
    val alignment = when (spec.moveTo) {
        ToolWindowMoveTo.LEFT_TOP, ToolWindowMoveTo.LEFT_BOTTOM -> Alignment.TopStart
        ToolWindowMoveTo.RIGHT_TOP, ToolWindowMoveTo.RIGHT_BOTTOM -> Alignment.TopEnd
        else -> Alignment.BottomCenter
    }
    val sizeModifier = if (dockedToBottom) {
        Modifier.fillMaxWidth().fillMaxHeight(paneShare).heightIn(min = BlazeLayout.StackedPaneMinHeight)
    } else {
        Modifier.fillMaxHeight().fillMaxWidth(paneShare).widthIn(min = BlazeLayout.OverlayPaneMinWidth)
    }
    val shape = RoundedCornerShape(BlazeLayout.IslandRadius)
    val gapPx = with(LocalDensity.current) { BlazeLayout.IslandGap.toPx() }

    Box(
        modifier = sizeModifier
            .align(alignment)
            // The layer's own child, so its parent bounds are the space the hit test reads. Reported
            // deflated by the gap the island keeps, so a press on that gap counts as leaving the pane.
            .onGloballyPositioned { coords -> onBounds(coords.boundsInParent().deflate(gapPx)) },
    ) {
        Island(
            modifier = Modifier
                .fillMaxSize()
                .padding(BlazeLayout.IslandGap)
                .shadow(BlazeLayout.OverlayPaneElevation, shape, clip = false),
            border = true,
        ) {
            spec.content()
        }
    }
}

/** Below this, a divider position is treated as the value it was asked for - rounding, not a drag. */
private const val FRACTION_EPSILON = 0.001f
