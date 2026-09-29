package org.blaze.presentation.screens.downloads

/**
 * The list's row selection, kept as pure data so the multi-select rules can be reasoned about and
 * unit-tested without a composition.
 *
 * [ids] is the current selection (unordered). [anchor] is the fixed end used by range operations
 * (Shift+Click / Shift+Arrow); [cursor] is the moving end. A plain click sets both to the clicked
 * row; a Ctrl/Cmd click toggles one row and recenters both; a Shift operation keeps [anchor] and
 * drags [cursor], so the selection is the contiguous span between them in list order.
 */
data class Selection(
    val ids: Set<String> = emptySet(),
    val anchor: String? = null,
    val cursor: String? = null,
) {
    val isEmpty: Boolean get() = ids.isEmpty()
    val size: Int get() = ids.size
    /** The single selected id when exactly one row is selected (drives the Details pane), else null. */
    val single: String? get() = ids.singleOrNull()
}

/** A user gesture that changes the selection, expressed against the list's current visible order. */
sealed interface SelectionAction {
    /** Pointer click on [id]. [additive] = Ctrl/Cmd (toggle), [range] = Shift (contiguous from anchor). */
    data class Click(val id: String, val additive: Boolean = false, val range: Boolean = false) : SelectionAction

    /** Select every visible row (Ctrl/Cmd+A, or the header / context-menu "Select All"). */
    data object SelectAll : SelectionAction

    /** Empty the selection (Escape, or clicking empty space). */
    data object Clear : SelectionAction

    /**
     * Keyboard navigation by [delta] rows (-1 up, +1 down). Without [extend] it collapses to the one
     * new row; with [extend] (Shift held) it drags the cursor, growing/shrinking the span from the anchor.
     */
    data class Move(val delta: Int, val extend: Boolean = false) : SelectionAction
}

/**
 * Applies [action] to [current] given the ordered [visible] row ids, returning the new [Selection].
 *
 * This is the single source of truth for the click / Ctrl / Shift / arrow rules, so the behaviour is
 * testable in isolation and every entry point (row pointer, keyboard, menus) agrees on the outcome.
 * Ids in [current] that are not in [visible] are preserved (the model prunes them against the live
 * list); range operations only ever reference rows that exist in [visible].
 */
fun reduceSelection(current: Selection, visible: List<String>, action: SelectionAction): Selection {
    if (visible.isEmpty()) return Selection()

    // Contiguous span between two ids in visible order; if an endpoint is gone, fall back to [b].
    fun span(a: String, b: String): Set<String> {
        val ia = visible.indexOf(a)
        val ib = visible.indexOf(b)
        if (ia < 0 || ib < 0) return setOf(b)
        val lo = minOf(ia, ib)
        val hi = maxOf(ia, ib)
        return visible.subList(lo, hi + 1).toSet()
    }

    return when (action) {
        SelectionAction.Clear -> Selection()

        SelectionAction.SelectAll -> Selection(visible.toSet(), visible.first(), visible.last())

        is SelectionAction.Click -> when {
            action.range -> {
                val base = current.anchor ?: action.id
                Selection(span(base, action.id), base, action.id)
            }
            action.additive -> {
                val ids = if (action.id in current.ids) current.ids - action.id else current.ids + action.id
                Selection(ids, action.id, action.id)
            }
            else -> Selection(setOf(action.id), action.id, action.id)
        }

        is SelectionAction.Move -> {
            val refId = current.cursor ?: current.anchor
            val startIndex = refId?.let { visible.indexOf(it) } ?: -1
            val targetIndex = if (startIndex < 0) 0 else (startIndex + action.delta).coerceIn(0, visible.lastIndex)
            val targetId = visible[targetIndex]
            if (action.extend) {
                val base = current.anchor ?: targetId
                Selection(span(base, targetId), base, targetId)
            } else {
                Selection(setOf(targetId), targetId, targetId)
            }
        }
    }
}
