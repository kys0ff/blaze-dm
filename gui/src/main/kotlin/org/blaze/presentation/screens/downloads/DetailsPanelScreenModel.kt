package org.blaze.presentation.screens.downloads

import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import org.blaze.presentation.components.panel.DetailsLayoutStore
import org.blaze.presentation.components.panel.MAX_SPLIT_FRACTION
import org.blaze.presentation.components.panel.MIN_SPLIT_FRACTION
import org.blaze.presentation.components.panel.ToolWindowLayout
import org.blaze.presentation.components.panel.ToolWindowMoveTo
import org.blaze.presentation.components.panel.ToolWindowResize
import org.blaze.presentation.components.panel.ToolWindowScreenModel
import org.blaze.presentation.components.panel.ToolWindowViewMode

/**
 * The Details tool window's own view state: its docked layout (inherited from [ToolWindowScreenModel])
 * folded with the one presentation flag that is Details-only — whether its tabs group into a single
 * continuous view. The list and its selection stay with [DownloadsScreenModel]; this model owns only the
 * pane the selection feeds.
 */
data class DetailsPanelState(
    val visible: Boolean,
    val viewMode: ToolWindowViewMode,
    val moveTo: ToolWindowMoveTo,
    /** Fraction of the list|details split given to the *list*, so the pane holds `1 - it`. */
    val splitFraction: Float,
    val innerSplitFraction: Float,
    val floatingOffsetX: Float,
    val floatingOffsetY: Float,
    val floatingWidthDp: Float,
    val floatingHeightDp: Float,
    val groupTabs: Boolean,
)

/**
 * The live Details tool-window model, now a first-class dockable pane exactly like Server Logs and
 * Extensions: its visibility, view mode, move-to, split and floating bounds are owned by
 * [ToolWindowScreenModel] and driven through [onLayoutEvent], persisted through [DetailsLayoutStore].
 * It adds only the pane's [groupTabs] flag and its pane-specific Resize presets.
 *
 * [setVisible] (inherited) is what the downloads list calls when the first row is selected, so the act
 * of selecting still earns the collapsed pane its place on the frame — but the visibility now lives in
 * one owner rather than leaking into the download screen model.
 */
class DetailsPanelScreenModel(
    private val detailsStore: DetailsLayoutStore,
) : ToolWindowScreenModel(detailsStore) {

    private val _state = MutableStateFlow(
        DetailsPanelState(
            visible = layout.value.visible,
            viewMode = layout.value.viewMode,
            moveTo = layout.value.moveTo,
            splitFraction = layout.value.splitFraction,
            innerSplitFraction = layout.value.innerSplitFraction,
            floatingOffsetX = layout.value.floatingOffsetX,
            floatingOffsetY = layout.value.floatingOffsetY,
            floatingWidthDp = layout.value.floatingWidthDp,
            floatingHeightDp = layout.value.floatingHeightDp,
            groupTabs = detailsStore.loadGroupTabs(),
        )
    )
    val state: StateFlow<DetailsPanelState> = _state.asStateFlow()

    /** Mirror the base's layout into the exposed state, leaving [groupTabs] untouched. */
    override fun onLayoutChanged(layout: ToolWindowLayout) {
        _state.update {
            it.copy(
                visible = layout.visible,
                viewMode = layout.viewMode,
                moveTo = layout.moveTo,
                splitFraction = layout.splitFraction,
                innerSplitFraction = layout.innerSplitFraction,
                floatingOffsetX = layout.floatingOffsetX,
                floatingOffsetY = layout.floatingOffsetY,
                floatingWidthDp = layout.floatingWidthDp,
                floatingHeightDp = layout.floatingHeightDp,
            )
        }
    }

    /**
     * A Resize preset names the edge the pane stretches toward, so it also docks the pane there where
     * that edge is a supported side; the size is kept as the list's share. Unlike the shared split
     * actions, the preset sets [moveTo] directly (a docked edge change, not a free-window re-anchor), so
     * it folds through [mutateLayout] rather than a bare [ToolWindowLayoutAction].
     */
    fun resize(preset: ToolWindowResize) {
        val current = currentLayout()
        val (fraction, moveTo) = when (preset) {
            ToolWindowResize.STRETCH_LEFT -> 0.30f to ToolWindowMoveTo.LEFT_TOP
            ToolWindowResize.STRETCH_RIGHT -> 0.30f to ToolWindowMoveTo.RIGHT_TOP
            ToolWindowResize.STRETCH_BOTTOM -> 0.30f to ToolWindowMoveTo.BOTTOM_LEFT
            ToolWindowResize.STRETCH_TOP -> 0.30f to current.moveTo
            ToolWindowResize.MAXIMIZE -> MIN_SPLIT_FRACTION to current.moveTo
        }
        mutateLayout {
            it.copy(
                splitFraction = fraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION),
                moveTo = moveTo,
            )
        }
    }

    fun setGroupTabs(enabled: Boolean) {
        if (_state.value.groupTabs == enabled) return
        _state.update { it.copy(groupTabs = enabled) }
        persistGroupTabs()
    }

    private var groupTabsPersistJob: Job? = null

    /** The group-tabs flag is Details-only, so it writes through the store on its own debounce. */
    private fun persistGroupTabs() {
        groupTabsPersistJob?.cancel()
        groupTabsPersistJob = screenModelScope.launch {
            delay(GROUP_TABS_PERSIST_DEBOUNCE_MS.milliseconds)
            detailsStore.saveGroupTabs(_state.value.groupTabs)
        }
    }

    private companion object {
        const val GROUP_TABS_PERSIST_DEBOUNCE_MS = 300L
    }
}
