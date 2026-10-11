package org.blaze.presentation.components.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.Island
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle

/**
 * Unified Tool Window Workspace host layout that handles multi-panel docking and floating windows.
 *
 * Docked panels are routed into 3 Dock Regions (Left, Right, Bottom) around the central content.
 * When multiple panels reside in the same region, an inner split layout divides the region between them.
 * Outer split layouts separate the central content area from the Left, Right, and Bottom dock regions.
 * Floating/undocked panels are rendered in an overlay layer with drag-moving and drag-resizing.
 */
@Composable
fun ToolWindowLayoutHost(
    mainContent: @Composable () -> Unit,
    panels: List<ToolWindowSpec>,
    modifier: Modifier = Modifier,
) {
    val activePanels = panels.filter { it.visible }
    val dockedPanels = activePanels.filter { it.viewMode.isDocked }
    val floatingPanels = activePanels.filter { !it.viewMode.isDocked }

    val leftPanels = dockedPanels
        .filter { it.moveTo == DetailsMoveTo.LEFT_TOP || it.moveTo == DetailsMoveTo.LEFT_BOTTOM }
        .sortedWith(compareBy { if (it.moveTo == DetailsMoveTo.LEFT_TOP) 0 else 1 })

    val rightPanels = dockedPanels
        .filter { it.moveTo == DetailsMoveTo.RIGHT_TOP || it.moveTo == DetailsMoveTo.RIGHT_BOTTOM }
        .sortedWith(compareBy { if (it.moveTo == DetailsMoveTo.RIGHT_TOP) 0 else 1 })

    val bottomPanels = dockedPanels
        .filter { it.moveTo == DetailsMoveTo.BOTTOM_LEFT || it.moveTo == DetailsMoveTo.BOTTOM_RIGHT }
        .sortedWith(compareBy { if (it.moveTo == DetailsMoveTo.BOTTOM_LEFT) 0 else 1 })

    val gapDivider = DividerStyle(
        color = Color.Transparent,
        metrics = DividerMetrics(thickness = BlazeLayout.IslandGap, startIndent = 0.dp),
    )

    // Helper to render docked region contents with optional inner split
    @Composable
    fun RenderRegionContent(
        regionPanels: List<ToolWindowSpec>,
        isHorizontalInner: Boolean,
    ): @Composable () -> Unit = {
        if (regionPanels.size == 1) {
            Island(modifier = Modifier.fillMaxSize()) {
                regionPanels.first().content()
            }
        } else if (regionPanels.size >= 2) {
            val firstPanel = regionPanels[0]
            val secondPanel = regionPanels[1]
            val innerFraction = firstPanel.innerSplitFraction
            val splitState = remember(firstPanel.id, secondPanel.id) { SplitLayoutState(innerFraction) }

            LaunchedEffect(innerFraction, splitState) {
                splitState.dividerPosition = innerFraction
            }
            LaunchedEffect(splitState) {
                snapshotFlow { splitState.dividerPosition }
                    .drop(1)
                    .collect { pos ->
                        firstPanel.onInnerSplitFractionChange(pos)
                        secondPanel.onInnerSplitFractionChange(1f - pos)
                    }
            }

            val firstPane: @Composable () -> Unit = { Island(modifier = Modifier.fillMaxSize()) { firstPanel.content() } }
            val secondPane: @Composable () -> Unit = { Island(modifier = Modifier.fillMaxSize()) { secondPanel.content() } }

            if (isHorizontalInner) {
                HorizontalSplitLayout(
                    first = firstPane,
                    second = secondPane,
                    modifier = Modifier.fillMaxSize(),
                    dividerStyle = gapDivider,
                    firstPaneMinWidth = 160.dp,
                    secondPaneMinWidth = 160.dp,
                    draggableWidth = BlazeLayout.SplitDragWidth,
                    state = splitState,
                )
            } else {
                VerticalSplitLayout(
                    first = firstPane,
                    second = secondPane,
                    modifier = Modifier.fillMaxSize(),
                    dividerStyle = gapDivider,
                    firstPaneMinWidth = 120.dp,
                    secondPaneMinWidth = 120.dp,
                    draggableWidth = BlazeLayout.SplitDragWidth,
                    state = splitState,
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize())
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Step 1: Base Central Content
        var currentWorkspace: @Composable () -> Unit = mainContent

        // Step 2: Left Dock Outer Split
        if (leftPanels.isNotEmpty()) {
            val primaryLeft = leftPanels.first()
            val leftContent = RenderRegionContent(leftPanels, isHorizontalInner = false)
            val outerFraction = 1f - primaryLeft.splitFraction
            val leftSplitState = remember(primaryLeft.id, leftPanels.size) { SplitLayoutState(outerFraction) }

            LaunchedEffect(outerFraction, leftSplitState) {
                leftSplitState.dividerPosition = outerFraction
            }
            LaunchedEffect(leftSplitState) {
                snapshotFlow { leftSplitState.dividerPosition }
                    .drop(1)
                    .collect { pos ->
                        val listFraction = 1f - pos
                        leftPanels.forEach { it.onSplitFractionChange(listFraction) }
                    }
            }

            val prevWorkspace = currentWorkspace
            currentWorkspace = {
                HorizontalSplitLayout(
                    first = leftContent,
                    second = prevWorkspace,
                    modifier = Modifier.fillMaxSize(),
                    dividerStyle = gapDivider,
                    firstPaneMinWidth = BlazeLayout.DetailsPaneMinWidth,
                    secondPaneMinWidth = BlazeLayout.ListPaneMinWidth,
                    draggableWidth = BlazeLayout.SplitDragWidth,
                    state = leftSplitState,
                )
            }
        }

        // Step 3: Right Dock Outer Split
        if (rightPanels.isNotEmpty()) {
            val primaryRight = rightPanels.first()
            val rightContent = RenderRegionContent(rightPanels, isHorizontalInner = false)
            val outerFraction = primaryRight.splitFraction
            val rightSplitState = remember(primaryRight.id, rightPanels.size) { SplitLayoutState(outerFraction) }

            LaunchedEffect(outerFraction, rightSplitState) {
                rightSplitState.dividerPosition = outerFraction
            }
            LaunchedEffect(rightSplitState) {
                snapshotFlow { rightSplitState.dividerPosition }
                    .drop(1)
                    .collect { pos ->
                        rightPanels.forEach { it.onSplitFractionChange(pos) }
                    }
            }

            val prevWorkspace = currentWorkspace
            currentWorkspace = {
                HorizontalSplitLayout(
                    first = prevWorkspace,
                    second = rightContent,
                    modifier = Modifier.fillMaxSize(),
                    dividerStyle = gapDivider,
                    firstPaneMinWidth = BlazeLayout.ListPaneMinWidth,
                    secondPaneMinWidth = BlazeLayout.DetailsPaneMinWidth,
                    draggableWidth = BlazeLayout.SplitDragWidth,
                    state = rightSplitState,
                )
            }
        }

        // Step 4: Bottom Dock Outer Split
        if (bottomPanels.isNotEmpty()) {
            val primaryBottom = bottomPanels.first()
            val bottomContent = RenderRegionContent(bottomPanels, isHorizontalInner = true)
            val outerFraction = primaryBottom.splitFraction
            val bottomSplitState = remember(primaryBottom.id, bottomPanels.size) { SplitLayoutState(outerFraction) }

            LaunchedEffect(outerFraction, bottomSplitState) {
                bottomSplitState.dividerPosition = outerFraction
            }
            LaunchedEffect(bottomSplitState) {
                snapshotFlow { bottomSplitState.dividerPosition }
                    .drop(1)
                    .collect { pos ->
                        bottomPanels.forEach { it.onSplitFractionChange(pos) }
                    }
            }

            val prevWorkspace = currentWorkspace
            currentWorkspace = {
                VerticalSplitLayout(
                    first = prevWorkspace,
                    second = bottomContent,
                    modifier = Modifier.fillMaxSize(),
                    dividerStyle = gapDivider,
                    firstPaneMinWidth = 200.dp,
                    secondPaneMinWidth = 120.dp,
                    draggableWidth = BlazeLayout.SplitDragWidth,
                    state = bottomSplitState,
                )
            }
        }

        // Render outer workspace
        currentWorkspace()

        // Step 5: Floating Layer Overlay
        if (floatingPanels.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                floatingPanels.forEach { panelSpec ->
                    FloatingToolWindow(
                        spec = panelSpec,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
