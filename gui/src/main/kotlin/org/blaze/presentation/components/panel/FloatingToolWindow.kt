package org.blaze.presentation.components.panel

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.Island
import org.blaze.presentation.components.ToolWindowHeader
import org.blaze.presentation.components.ToolWindowOptionsButton
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt

@Composable
fun FloatingToolWindow(
    spec: ToolWindowSpec,
    modifier: Modifier = Modifier,
) {
    var size by remember(spec.floatingSize) { mutableStateOf(spec.floatingSize) }
    val strings = blazeStrings
    val options = strings.downloads.detailsPane.options

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        val effectiveOffset = remember(spec.floatingOffset, spec.moveTo, containerWidth, containerHeight, size) {
            if (spec.floatingOffset != Offset.Zero) {
                spec.floatingOffset
            } else {
                with(density) {
                    val widthPx = size.width.toPx()
                    val heightPx = size.height.toPx()
                    val maxX = (containerWidth.toPx() - widthPx).coerceAtLeast(16f)
                    val maxY = (containerHeight.toPx() - heightPx).coerceAtLeast(16f)
                    when (spec.moveTo) {
                        DetailsMoveTo.LEFT_TOP -> Offset(16f, 16f)
                        DetailsMoveTo.LEFT_BOTTOM -> Offset(16f, maxY - 16f)
                        DetailsMoveTo.RIGHT_TOP -> Offset(maxX - 16f, 16f)
                        DetailsMoveTo.RIGHT_BOTTOM -> Offset(maxX - 16f, maxY - 16f)
                        DetailsMoveTo.BOTTOM_LEFT -> Offset(16f, maxY - 16f)
                        DetailsMoveTo.BOTTOM_RIGHT -> Offset(maxX - 16f, maxY - 16f)
                    }
                }
            }
        }

        var offset by remember(effectiveOffset) { mutableStateOf(effectiveOffset) }

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .size(size)
        ) {
            Island(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    with(density) {
                                        val newX = (offset.x + dragAmount.x).coerceIn(0f, (containerWidth.toPx() - 100f).coerceAtLeast(0f))
                                        val newY = (offset.y + dragAmount.y).coerceIn(0f, (containerHeight.toPx() - 60f).coerceAtLeast(0f))
                                        val newOffset = Offset(newX, newY)
                                        offset = newOffset
                                        spec.onFloatingBoundsChange(newOffset, size)
                                    }
                                }
                            }
                    ) {
                        ToolWindowHeader(
                            title = spec.title,
                            actions = {
                                ToolWindowOptionsButton(
                                    options = options,
                                    viewMode = spec.viewMode,
                                    moveTo = spec.moveTo,
                                    onViewMode = spec.onViewMode,
                                    onMoveTo = spec.onMoveTo,
                                    onResize = spec.onResize,
                                )
                                ToolbarIconButton(
                                    key = AllIconsKeys.General.HideToolWindow,
                                    tooltip = strings.downloads.detailsPane.hide,
                                    onClick = spec.onClose,
                                )
                            }
                        )
                    }

                    Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                        spec.content()
                    }
                }
            }

            // Interactive bottom-right resize handle
            val resizeHandleSize = 16.dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(resizeHandleSize)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            with(density) {
                                val newWidth = (size.width.toPx() + dragAmount.x).toDp().coerceIn(240.dp, (containerWidth - 20.dp).coerceAtLeast(240.dp))
                                val newHeight = (size.height.toPx() + dragAmount.y).toDp().coerceIn(180.dp, (containerHeight - 20.dp).coerceAtLeast(180.dp))
                                val newSize = DpSize(newWidth, newHeight)
                                size = newSize
                                spec.onFloatingBoundsChange(offset, newSize)
                            }
                        }
                    }
            )
        }
    }
}
