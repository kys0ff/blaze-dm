package org.blaze.presentation.components.panel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The behavior every dockable tool window shares: a [ToolWindowLayout] held in a synchronous flow, the
 * single [reduceToolWindowLayout] that decides how an action changes it, and one debounced write-back
 * through a pane's [ToolWindowLayoutStore]. Subclasses get the layout logic for free and add only what
 * is genuinely theirs (a pane's rows, a find bar, a resize preset).
 *
 * Layout is exposed as [layout] and mirrored to subclasses through [onLayoutChanged] so a subclass can
 * fold it into its own combined, synchronous `state` for the view. Mutation is intentionally funneled
 * through [onLayoutEvent]/[setVisible]/[toggleVisible] — there are no public raw setters, which is the
 * whole point of the base: the three panes used to publish `setViewMode`/`setMoveTo`/`resize` directly
 * and drifted into three near-copies of the same rules.
 */
abstract class ToolWindowScreenModel(
    private val store: ToolWindowLayoutStore,
) : ScreenModel {

    private val _layout = MutableStateFlow(store.load())

    /** The pane's current docked layout. Synchronous so `.value` is always the live layout. */
    val layout: StateFlow<ToolWindowLayout> = _layout.asStateFlow()

    private var persistJob: Job? = null

    /** Folds a layout [action] through the shared reducer and schedules a persist. */
    fun onLayoutEvent(action: ToolWindowLayoutAction) =
        mutateLayout { reduceToolWindowLayout(it, action) }

    /** Raises or lowers the pane. A no-op when already in [visible], so an unchanged toggle never writes. */
    fun setVisible(visible: Boolean) {
        if (_layout.value.visible == visible) return
        mutateLayout { it.copy(visible = visible) }
    }

    fun toggleVisible() = setVisible(!_layout.value.visible)

    /**
     * Applies a subclass-owned layout change (a pane-specific Resize preset) as a single synchronous
     * fold plus a persist, reusing the exact same plumbing as [onLayoutEvent] rather than a parallel
     * write path. Layout is only ever mutated through here.
     */
    protected fun mutateLayout(transform: (ToolWindowLayout) -> ToolWindowLayout) {
        _layout.update(transform)
        onLayoutChanged(_layout.value)
        persist()
    }

    protected fun currentLayout(): ToolWindowLayout = _layout.value

    /**
     * Hook for a subclass to mirror the new [layout] into its own combined `state`. Called on every
     * mutation (never during construction, so subclass property initializers are ready by then).
     */
    protected open fun onLayoutChanged(layout: ToolWindowLayout) = Unit

    /** Coalesces rapid layout edits (divider / drag / repeated toggles) into one debounced disk write. */
    private fun persist() {
        persistJob?.cancel()
        persistJob = screenModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS.milliseconds)
            store.save(_layout.value)
        }
    }

    companion object {
        /** How long to wait after the last layout tweak before writing through the store. */
        const val PERSIST_DEBOUNCE_MS = 300L

        /** The step a "stretch" resize preset moves the split by. */
        const val RESIZE_STEP = 0.15f
    }
}
