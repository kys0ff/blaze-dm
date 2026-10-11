package org.blaze.presentation.screens.serverlog

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blaze.data.AppSettingsRepository
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.blaze.presentation.screens.downloads.DetailsResize
import org.blaze.presentation.screens.downloads.DetailsViewMode
import org.blaze.presentation.screens.downloads.detailsMoveToFromName
import org.blaze.presentation.screens.downloads.detailsViewModeFromName
import kotlin.time.Duration.Companion.milliseconds

/**
 * Everything the Server Logs tool window presents and persists: its visibility, its docked layout (the
 * same view-mode / move-to / resize model the Details pane uses), and its in-panel find state. The layout
 * seeds from [AppSettingsRepository] on construction and is written back, debounced, on change; the find
 * state is pure in-memory reduce that never outlives the window.
 */
data class ServerLogWindowState(
    val visible: Boolean,
    val viewMode: DetailsViewMode,
    val moveTo: DetailsMoveTo,
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

class ServerLogWindowModel(
    private val appSettingsRepository: AppSettingsRepository,
) : ScreenModel {

    private val _state = MutableStateFlow(
        appSettingsRepository.settings.value.let { saved ->
            ServerLogWindowState(
                visible = saved.serverLogVisible,
                viewMode = detailsViewModeFromName(saved.serverLogViewMode),
                moveTo = detailsMoveToFromName(saved.serverLogMoveTo),
                splitFraction = saved.serverLogSplitFraction,
                innerSplitFraction = saved.serverLogInnerSplitFraction,
                floatingOffsetX = saved.serverLogFloatingOffsetX,
                floatingOffsetY = saved.serverLogFloatingOffsetY,
                floatingWidthDp = saved.serverLogFloatingWidthDp,
                floatingHeightDp = saved.serverLogFloatingHeightDp,
                findBarOpen = false,
                findQuery = "",
                matchCount = 0,
                currentMatch = 0,
            )
        }
    )
    val state: StateFlow<ServerLogWindowState> = _state.asStateFlow()

    private var persistJob: Job? = null

    /** Raises or lowers the window; the "ShowServerLog" command toggles, the header's Hide closes. */
    fun toggleVisible() = setVisible(!_state.value.visible)

    fun setVisible(visible: Boolean) {
        if (_state.value.visible == visible) return
        _state.update { it.copy(visible = visible) }
        persist()
    }

    fun setViewMode(mode: DetailsViewMode) {
        _state.update { it.copy(viewMode = mode) }
        persist()
    }

    fun setMoveTo(position: DetailsMoveTo) {
        _state.update { it.copy(moveTo = position) }
        persist()
    }

    fun setSplitFraction(fraction: Float) {
        _state.update { it.copy(splitFraction = fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)) }
        persist()
    }

    fun setInnerSplitFraction(fraction: Float) {
        _state.update { it.copy(innerSplitFraction = fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)) }
        persist()
    }

    fun setFloatingBounds(x: Float, y: Float, widthDp: Float, heightDp: Float) {
        _state.update {
            it.copy(
                floatingOffsetX = x,
                floatingOffsetY = y,
                floatingWidthDp = widthDp,
                floatingHeightDp = heightDp,
            )
        }
        persist()
    }

    /** A Resize preset shifts the content's share of the split; the log window grows as content shrinks. */
    fun resize(preset: DetailsResize) {
        val fraction = when (preset) {
            DetailsResize.STRETCH_TOP, DetailsResize.STRETCH_LEFT -> _state.value.splitFraction + RESIZE_STEP
            DetailsResize.STRETCH_BOTTOM, DetailsResize.STRETCH_RIGHT -> _state.value.splitFraction - RESIZE_STEP
            DetailsResize.MAXIMIZE -> MIN_FRACTION
        }.coerceIn(MIN_FRACTION, MAX_FRACTION)
        _state.update { it.copy(splitFraction = fraction) }
        persist()
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

    // ── persistence ──────────────────────────────────────────────────────────────

    private fun persist() {
        persistJob?.cancel()
        persistJob = screenModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS.milliseconds)
            val s = _state.value
            appSettingsRepository.updateSettings {
                it.copy(
                    serverLogVisible = s.visible,
                    serverLogSplitFraction = s.splitFraction,
                    serverLogInnerSplitFraction = s.innerSplitFraction,
                    serverLogFloatingOffsetX = s.floatingOffsetX,
                    serverLogFloatingOffsetY = s.floatingOffsetY,
                    serverLogFloatingWidthDp = s.floatingWidthDp,
                    serverLogFloatingHeightDp = s.floatingHeightDp,
                    serverLogViewMode = s.viewMode.name,
                    serverLogMoveTo = s.moveTo.name,
                )
            }
        }
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 300L
        const val MIN_FRACTION = 0.15f
        const val MAX_FRACTION = 0.85f
        const val RESIZE_STEP = 0.15f
    }
}
