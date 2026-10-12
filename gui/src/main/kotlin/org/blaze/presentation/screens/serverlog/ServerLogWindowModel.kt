package org.blaze.presentation.screens.serverlog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.blaze.data.AppSettingsRepository
import org.blaze.presentation.components.panel.MIN_SPLIT_FRACTION
import org.blaze.presentation.components.panel.ServerLogLayoutStore
import org.blaze.presentation.components.panel.ToolWindowLayout
import org.blaze.presentation.components.panel.ToolWindowLayoutAction
import org.blaze.presentation.components.panel.ToolWindowMoveTo
import org.blaze.presentation.components.panel.ToolWindowResize
import org.blaze.presentation.components.panel.ToolWindowScreenModel
import org.blaze.presentation.components.panel.ToolWindowViewMode

/**
 * Everything the Server Logs tool window presents: its docked layout (inherited from
 * [ToolWindowScreenModel], persisted through [ServerLogLayoutStore]) and its in-panel find state, which
 * is pure in-memory reduce that never outlives the window. The [state] below folds the two together so a
 * single collect drives the whole pane.
 */
data class ServerLogWindowState(
    val visible: Boolean,
    val viewMode: ToolWindowViewMode,
    val moveTo: ToolWindowMoveTo,
    /** Fraction of the split given to the *content* (everything but the log window). */
    val splitFraction: Float,
    val innerSplitFraction: Float = 0.50f,
    val floatingOffsetX: Float = 80f,
    val floatingOffsetY: Float = 80f,
    val floatingWidthDp: Float = 450f,
    val floatingHeightDp: Float = 320f,
    val findBarOpen: Boolean,
    val findQuery: String,
    /** Total matches the panel counted for the current [findQuery]; 0 hides the counter's position. */
    val matchCount: Int,
    /** Index of the focused match within the [matchCount], or 0 when there are none. */
    val currentMatch: Int,
)

/**
 * The Server Logs window model. Its docked layout — visibility, view mode, move-to, split and floating
 * bounds — is owned entirely by [ToolWindowScreenModel]; only the find state lives here. Layout changes
 * arrive as [ToolWindowLayoutAction]s through [onLayoutEvent]; there are no raw layout setters, so the
 * view and the shell drive this window exactly the way they drive every other pane.
 */
class ServerLogWindowModel(
    appSettingsRepository: AppSettingsRepository,
) : ToolWindowScreenModel(ServerLogLayoutStore(appSettingsRepository)) {

    private val _state = MutableStateFlow(
        ServerLogWindowState(
            visible = layout.value.visible,
            viewMode = layout.value.viewMode,
            moveTo = layout.value.moveTo,
            splitFraction = layout.value.splitFraction,
            innerSplitFraction = layout.value.innerSplitFraction,
            floatingOffsetX = layout.value.floatingOffsetX,
            floatingOffsetY = layout.value.floatingOffsetY,
            floatingWidthDp = layout.value.floatingWidthDp,
            floatingHeightDp = layout.value.floatingHeightDp,
            findBarOpen = false,
            findQuery = "",
            matchCount = 0,
            currentMatch = 0,
        )
    )
    val state: StateFlow<ServerLogWindowState> = _state.asStateFlow()

    /** Mirror the base's layout into the exposed state, leaving the find fields untouched. */
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
     * A Resize preset shifts the content's share of the split (the log window grows as content shrinks);
     * MAXIMIZE claims everything down to the floor. Composed from the shared [ToolWindowLayoutAction] so
     * it reuses the reducer's clamp rather than a bespoke write.
     */
    fun resize(preset: ToolWindowResize) {
        val fraction = when (preset) {
            ToolWindowResize.STRETCH_TOP, ToolWindowResize.STRETCH_LEFT -> currentLayout().splitFraction + RESIZE_STEP
            ToolWindowResize.STRETCH_BOTTOM, ToolWindowResize.STRETCH_RIGHT -> currentLayout().splitFraction - RESIZE_STEP
            ToolWindowResize.MAXIMIZE -> MIN_SPLIT_FRACTION
        }
        onLayoutEvent(ToolWindowLayoutAction.SetSplitFraction(fraction))
    }

    // ── find ────────────────────────────────────────────────────────────────────

    fun openFind() = _state.update { it.copy(findBarOpen = true) }

    fun closeFind() {
        _state.update { it.copy(findBarOpen = false, findQuery = "", matchCount = 0, currentMatch = 0) }
    }

    fun toggleFind() {
        if (_state.value.findBarOpen) closeFind() else openFind()
    }

    fun setFindQuery(query: String) {
        // A new query resets navigation; the panel recounts matches and reports the new total.
        _state.update { it.copy(findQuery = query, currentMatch = 0) }
    }

    /** Called by the panel after it recomputes matches, so the counter and the focus stay in range. */
    fun setMatches(total: Int) {
        _state.update {
            val current = if (total == 0) 0 else it.currentMatch.coerceIn(0, total - 1)
            it.copy(matchCount = total, currentMatch = current)
        }
    }

    fun nextMatch() = stepMatch(1)

    fun previousMatch() = stepMatch(-1)

    private fun stepMatch(delta: Int) {
        val total = _state.value.matchCount
        if (total == 0) return
        _state.update { it.copy(currentMatch = ((it.currentMatch + delta) % total + total) % total) }
    }
}
