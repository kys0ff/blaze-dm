package org.island.ui.toolwindow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import org.island.theme.ui.IslandLayout
import org.island.ui.IslandPanel
import org.island.ui.text.LocalIslandUiStrings
import org.island.ui.toolbar.IslandToolbarIconButton
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.Cursor
import kotlin.math.roundToInt

/** The resolved geometry of a floating tool window, in pixels inside the overlay layer. */
data class FloatingBoundsPx(val offset: Offset, val sizePx: IntSize)

/**
 * The geometry a floating window may actually occupy inside a layer of [containerPx]: [sizePx] clamped
 * between the window's own minimum and the layer, and the position moved so the whole window stays
 * inside it.
 *
 * The position clamp is the point of the function: a window whose title bar leaves the layer cannot be
 * dragged back, so a pane restored from settings written by a wider window - or dragged toward an edge
 * - is pulled fully back on screen instead of becoming unreachable.
 */
fun clampFloatingBounds(
    offset: Offset,
    sizePx: IntSize,
    containerPx: IntSize,
    minSizePx: IntSize,
): FloatingBoundsPx {
    if (containerPx.width <= 0 || containerPx.height <= 0) return FloatingBoundsPx(offset, sizePx)
    val width = sizePx.width.coerceIn(minSizePx.width, containerPx.width)
    val height = sizePx.height.coerceIn(minSizePx.height, containerPx.height)
    val maxX = (containerPx.width - width).coerceAtLeast(0).toFloat()
    val maxY = (containerPx.height - height).coerceAtLeast(0).toFloat()
    return FloatingBoundsPx(
        offset = Offset(offset.x.coerceIn(0f, maxX), offset.y.coerceIn(0f, maxY)),
        sizePx = IntSize(width, height),
    )
}

/**
 * Where a window with no place of its own sits: hugging the corner of the edge it is docked to (see
 * [ToolWindowMoveTo]), inset by [marginPx] so it clears the island gap. The same corner answers a "Move
 * To" made while the window was free, which is what makes that submenu row mean something up here.
 */
fun anchoredOffset(
    moveTo: ToolWindowMoveTo,
    sizePx: IntSize,
    containerPx: IntSize,
    marginPx: Float,
): Offset {
    val maxX = (containerPx.width - sizePx.width).toFloat().coerceAtLeast(marginPx)
    val maxY = (containerPx.height - sizePx.height).toFloat().coerceAtLeast(marginPx)
    val x = when (moveTo) {
        ToolWindowMoveTo.LEFT_TOP, ToolWindowMoveTo.LEFT_BOTTOM -> marginPx
        else -> maxX - marginPx
    }
    val y = when (moveTo) {
        ToolWindowMoveTo.LEFT_TOP, ToolWindowMoveTo.RIGHT_TOP -> marginPx
        else -> maxY - marginPx
    }
    return Offset(x, y)
}

/**
 * A tool window drawn *over* the workspace: it can be dragged by its title bar and resized from its
 * bottom-right corner, and it never reserves a share of the frame.
 *
 * The pane's own header is the window's title bar — [ToolWindowHeader] picks the drag surface up from
 * [LocalToolWindowDragHandle] — so there is exactly one header per window, with the pane's own options
 * and hide buttons. [FloatingBoundsPx] is the single live geometry: it follows the spec whenever
 * something outside moved it (a Resize preset, the restore at startup) and stops following it while a
 * drag is writing through the model, which is what used to make the window jump mid-drag.
 */
@Composable
fun FloatingToolWindow(
    spec: ToolWindowSpec,
    modifier: Modifier = Modifier,
    onBounds: (Rect) -> Unit = {},
) {
    val density = LocalDensity.current
    val strings = LocalIslandUiStrings.current

    val minSizePx = with(density) {
        IntSize(IslandLayout.FloatingPaneMinWidth.roundToPx(), IslandLayout.FloatingPaneMinHeight.roundToPx())
    }
    val marginPx = with(density) { IslandLayout.FloatingPaneMargin.toPx() }

    // Live container size and live bounds, both as state: the gesture coroutines read the current
    // values instead of the ones captured when they started, so neither a frame resize nor the window's
    // own growth makes a drag snap the pane back.
    var containerPx by remember { mutableStateOf(IntSize.Zero) }
    var bounds by remember { mutableStateOf(initialBounds(spec, minSizePx, density)) }
    var lastReported by remember { mutableStateOf<FloatingBounds?>(null) }

    val specBounds by rememberUpdatedState(FloatingBounds(spec.floatingOffset, spec.floatingSize))
    val geometry by rememberUpdatedState(FloatingGeometry(density, containerPx, minSizePx, marginPx))
    val report by rememberUpdatedState<(FloatingBoundsPx) -> Unit> { value ->
        val dp = value.toDpBounds(geometry.density)
        lastReported = dp
        spec.onFloatingBoundsChange(dp.offset, dp.size)
    }

    // An outside change (a Resize preset, a restore) is applied; a value coming back that matches what
    // this window just wrote is that same write echoed through the model and must not be re-applied.
    LaunchedEffect(specBounds) {
        if (specBounds != lastReported) bounds = specBounds.toPxBounds(geometry.density, geometry.minSizePx)
    }

    // The corner the window was last placed at, so a drag or a resize is never undone by re-anchoring.
    var anchoredTo by remember { mutableStateOf<ToolWindowMoveTo?>(null) }

    // The layer is what gives an unplaced window its corner, and what pulls a pane that no longer fits
    // back onto the screen. Both are re-checked whenever either side changes size or the edge changes.
    LaunchedEffect(bounds, containerPx, spec.moveTo, spec.floatingOffset) {
        val g = geometry
        if (g.containerPx.width > 0 && g.containerPx.height > 0) {
            // An owner that cleared its offset is asking for a corner: a window that just detached from
            // a dock edge, or a free window whose "Move To" picked a different one. Reporting the place
            // it landed makes the offset non-zero again, so this runs once per request.
            val needsCorner = spec.floatingOffset == Offset.Zero && anchoredTo != spec.moveTo
            if (needsCorner) anchoredTo = spec.moveTo
            val candidate = if (needsCorner) {
                bounds.copy(offset = anchoredOffset(spec.moveTo, bounds.sizePx, g.containerPx, g.marginPx))
            } else {
                bounds
            }
            val clamped = clampFloatingBounds(candidate.offset, candidate.sizePx, g.containerPx, g.minSizePx)
            if (clamped != bounds) {
                bounds = clamped
                report(clamped)
            }
        }
    }

    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            val g = geometry
            val moved = clampFloatingBounds(
                offset = bounds.offset + dragAmount,
                sizePx = bounds.sizePx,
                containerPx = g.containerPx,
                minSizePx = g.minSizePx,
            )
            if (moved != bounds) {
                bounds = moved
                report(moved)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerPx = it },
    ) {
        val sizeDp = with(density) { DpSize(bounds.sizePx.width.toDp(), bounds.sizePx.height.toDp()) }
        Box(
            modifier = Modifier
                .offset { IntOffset(bounds.offset.x.roundToInt(), bounds.offset.y.roundToInt()) }
                .size(sizeDp)
                .shadow(
                    elevation = IslandLayout.FloatingPaneElevation,
                    shape = IslandShape,
                    clip = false,
                )
                .onGloballyPositioned { coords -> onBounds(coords.boundsInParent()) },
        ) {
            IslandPanel(modifier = Modifier.fillMaxSize(), border = true) {
                if (spec.hasOwnHeader) {
                    // The pane draws its own header, so it *is* the title bar: it gets the drag surface.
                    CompositionLocalProvider(LocalToolWindowDragHandle provides dragModifier) {
                        spec.content()
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalToolWindowDragHandle provides dragModifier) {
                            ToolWindowHeader(
                                title = spec.title,
                                actions = {
                                    ToolWindowOptionsButton(
                                        viewMode = spec.viewMode,
                                        moveTo = spec.moveTo,
                                        onViewMode = spec.onViewMode,
                                        onMoveTo = spec.onMoveTo,
                                        onResize = spec.onResize,
                                    )
                                    IslandToolbarIconButton(
                                        key = AllIconsKeys.General.HideToolWindow,
                                        tooltip = strings.toolWindowHide(),
                                        onClick = spec.onClose,
                                    )
                                },
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { spec.content() }
                    }
                }
            }

            // The resize grip: an IDE-style pair of hairlines in the corner, over a generous hit area.
            val gripColor = JewelTheme.globalColors.borders.normal
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(IslandLayout.FloatingResizeHandleSize)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val g = geometry
                            val resized = clampFloatingBounds(
                                offset = bounds.offset,
                                sizePx = IntSize(
                                    bounds.sizePx.width + dragAmount.x.roundToInt(),
                                    bounds.sizePx.height + dragAmount.y.roundToInt(),
                                ),
                                containerPx = g.containerPx,
                                minSizePx = g.minSizePx,
                            )
                            if (resized != bounds) {
                                bounds = resized
                                report(resized)
                            }
                        }
                    },
                contentAlignment = Alignment.BottomEnd,
            ) {
                Canvas(modifier = Modifier.size(IslandLayout.FloatingResizeHandleSize)) {
                    val unit = 3.dp.toPx()
                    val right = size.width - unit
                    val bottom = size.height - unit
                    for (step in 0..2) {
                        val inset = step * unit
                        drawLine(
                            color = gripColor,
                            start = Offset(right - inset, bottom),
                            end = Offset(right, bottom - inset),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
            }
        }
    }
}

/** The density and geometry a gesture needs, read live so a coroutine never works from a stale frame. */
private class FloatingGeometry(
    val density: Density,
    val containerPx: IntSize,
    val minSizePx: IntSize,
    val marginPx: Float,
)

private val IslandShape = RoundedCornerShape(IslandLayout.IslandRadius)

/** The persisted (dp) bounds resolved into pixels for drawing and hit-testing. */
private fun FloatingBounds.toPxBounds(density: Density, minSizePx: IntSize): FloatingBoundsPx {
    val sizePx = with(density) { IntSize(size.width.roundToPx(), size.height.roundToPx()) }
    return FloatingBoundsPx(offset = offset, sizePx = sizePx.coerceAtLeast(minSizePx))
}

private fun FloatingBoundsPx.toDpBounds(density: Density): FloatingBounds = with(density) {
    FloatingBounds(offset = offset, size = DpSize(sizePx.width.toDp(), sizePx.height.toDp()))
}

/** Where the window starts before the layer has measured itself: the spec, or the corner it will hug. */
private fun initialBounds(spec: ToolWindowSpec, minSizePx: IntSize, density: Density): FloatingBoundsPx =
    FloatingBounds(spec.floatingOffset, spec.floatingSize).toPxBounds(density, minSizePx)

private fun IntSize.coerceAtLeast(other: IntSize): IntSize =
    IntSize(width.coerceAtLeast(other.width), height.coerceAtLeast(other.height))
