package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.MenuStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.menuStyle
import org.jetbrains.jewel.ui.typography
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * One row of a [BlazeMenu].
 *
 * The menu is data, not a DSL of composable lambdas: owning the whole item list as a value is what
 * lets one surface hit-test the pointer across every level (the reason [BlazeMenu] exists), and it is
 * what lets the row context menu, the header's Table options and the tool-window "more" menus share
 * one renderer.
 */
sealed class MenuEntry {
    abstract val label: String
    abstract val enabled: Boolean

    /**
     * A leaf item. [checked] drives the icon column: `null` is an ordinary row, `true`/`false` is a
     * check or radio row, which the IDE marks in the icon column rather than with a box.
     */
    data class Action(
        override val label: String,
        val icon: IconKey? = null,
        val accelerator: String? = null,
        override val enabled: Boolean = true,
        val checked: Boolean? = null,
        val onClick: () -> Unit = {},
    ) : MenuEntry()

    /** A row that owns a nested level. [children] are read on hover, so they can be built lazily. */
    data class Submenu(
        override val label: String,
        val icon: IconKey? = null,
        val accelerator: String? = null,
        override val enabled: Boolean = true,
        val children: List<MenuEntry>,
    ) : MenuEntry()

    /** A group boundary: 1dp of [MenuStyle][org.jetbrains.jewel.ui.component.styling.MenuColors]
     * separator color with the theme's own vertical margins. */
    data object Separator : MenuEntry() {
        override val label: String = ""
        override val enabled: Boolean = false
    }
}

/** The bounds a menu is opened against, in root coordinates: a button, or the point a right-click hit. */
@Stable
class MenuAnchor internal constructor(initial: Rect) {
    var bounds: Rect by mutableStateOf(initial)
}

/** Records the bounds of the control that opens a menu; apply [trackingModifier] to that control. */
@Composable
fun rememberMenuAnchor(): MenuAnchor = remember { MenuAnchor(Rect.Zero) }

/** Tracks the anchor's own bounds; use as the modifier of the box wrapping the opener control. */
val MenuAnchor.trackingModifier: Modifier
    get() = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }

/** A menu opened at a point (the cursor of a right-click) rather than under a control. */
@Composable
fun rememberMenuAnchorAt(position: Offset): MenuAnchor =
    remember(position) { MenuAnchor(Rect(position, Size(1f, 1f))) }

// ─────────────────────────────────────────────────────────────────────────────
// The state machine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One submission of a menu's rows, remembered by its *shape*: the labels, marks and nesting that are
 * actually drawn. Equality deliberately ignores the click lambdas, so handing in the same menu with
 * new closures changes no observable state and costs no frame, while a row appearing, disappearing or
 * changing name does.
 */
private class MenuPayload(val entries: List<MenuEntry>) {
    val shape: String = shapeOf(entries)

    override fun equals(other: Any?): Boolean = other is MenuPayload && other.shape == shape
    override fun hashCode(): Int = shape.hashCode()

    private companion object {
        fun shapeOf(entries: List<MenuEntry>): String = buildString {
            for (entry in entries) {
                append('|')
                when (entry) {
                    MenuEntry.Separator -> append('-')
                    is MenuEntry.Action -> {
                        if (entry.checked == true) append('*')
                        append(entry.label)
                        if (!entry.enabled) append('!')
                    }

                    is MenuEntry.Submenu -> {
                        append('>')
                        append(entry.label)
                        append(shapeOf(entry.children))
                    }
                }
            }
        }
    }
}

/**
 * The whole open menu: which submenu is expanded at each level, which row is highlighted in each
 * level, and the measured bounds of every level and row.
 *
 * Every transition is driven from *geometry* — the pointer position the surface itself observes —
 * rather than from Compose's per-item hover callbacks. That is deliberate and is the bug this
 * component fixes: while a nested menu popup exists, the level beneath it receives no pointer events
 * at all (measured, not assumed: a `MenuContent` box reports no `Enter` when the pointer returns from
 * an open submenu), so a state machine that waits for hover can only ever stay stuck.
 */
@Stable
internal class BlazeMenuModel(entries: List<MenuEntry>) {
    /**
     * The rows the levels render, and the only thing a swap of [submit] can make recompose.
     *
     * A menu's entries are rebuilt whenever the control behind it recomposes — which for a
     * downloading row is about once a second, with brand-new click lambdas every time. Keeping that
     * list in observed state would either throw the hover state away on each tick (if the model were
     * rebuilt) or recompose forever (each new list is structurally different, being full of fresh
     * closures). So the payload is remembered by its *shape* alone: rows and labels equal means the
     * same picture, and the closures are read at click time, never from the rendered frame.
     */
    private var payload by mutableStateOf(MenuPayload(entries))

    /** `opened[d]` is the index in level `d` of the submenu row that owns level `d + 1`. */
    private val opened: SnapshotStateList<Int> = mutableStateListOf()

    /** The highlighted row in each open level. */
    private val highlighted: SnapshotStateList<Int> = mutableStateListOf(0)

    private val _levelBounds = mutableStateMapOf<Int, Rect>()
    private val _rowBounds = mutableStateMapOf<Pair<Int, Int>, Rect>()

    var depth by mutableStateOf(1); private set

    /** Which level the keyboard (and the last pointer interaction) belongs to. */
    var activeLevel by mutableStateOf(0); private set

    /** False while the pointer is outside every level, which is what collapses open submenus. */
    var pointerInside by mutableStateOf(true); private set

    /** The submenu row the pointer is resting on but has not yet opened (the hover delay). */
    var pendingOpen by mutableStateOf<Pair<Int, Int>?>(null); private set

    fun levelBounds(level: Int): Rect? = _levelBounds[level]

    /** Adopts a freshly built item list, keeping the open levels and highlights where they still fit. */
    fun submit(entries: List<MenuEntry>) {
        val previous = payload
        val next = MenuPayload(entries)
        payload = next
        if (next.shape != previous.shape) resync()
    }

    /**
     * Re-renders after the shape changed: levels whose owner row stopped being a submenu close, and
     * highlights clamp into the new row count. Nothing else is disturbed, so a rename (Pause → Resume)
     * under a resting cursor updates in place instead of collapsing the menu.
     */
    private fun resync() {
        while (opened.isNotEmpty() && entriesAt(opened.size - 1).getOrNull(opened[opened.size - 1]) !is MenuEntry.Submenu) {
            opened.removeAt(opened.size - 1)
        }
        publishDepth(opened.size + 1)
        for (level in 0 until depth) {
            val rows = entriesAt(level)
            if (rows.isEmpty()) continue
            val target = highlightedAt(level).coerceIn(0, rows.lastIndex)
            if (highlighted[level] != target) highlighted[level] = target
        }
    }

    fun onLevelBounds(level: Int, rect: Rect) {
        _levelBounds[level] = rect
    }

    fun onRowBounds(level: Int, index: Int, rect: Rect) {
        _rowBounds[level to index] = rect
    }

    /** Whether the pointer at [position] is over the row [index] of [level] as actually drawn. */
    fun rowContains(level: Int, index: Int, position: Offset): Boolean =
        _rowBounds[level to index]?.contains(position) == true

    /** The measured box of the row that owns [childLevel], which is what the child hangs off. */
    fun parentRowBounds(childLevel: Int): Rect? {
        val index = opened.getOrNull(childLevel - 1) ?: return null
        return _rowBounds[(childLevel - 1) to index]
    }

    /**
     * Runs the row [index] of [level]. Returns `false` when a leaf fired and the whole menu should
     * close, `true` when it stays open (a submenu just expanded, or an inert row was pressed).
     */
    fun activateRow(level: Int, index: Int): Boolean {
        if (highlightedAt(level) != index) highlight(level, index, openImmediately = false)
        return when (val row = entryAt(level, index)) {
            is MenuEntry.Action -> {
                if (!row.enabled) return true
                row.onClick()
                false
            }

            is MenuEntry.Submenu -> {
                if (row.enabled) highlight(level, index, openImmediately = true)
                true
            }

            MenuEntry.Separator, null -> true
        }
    }

    /** The rows rendered at [level]; deeper levels come from the submenu chain. */
    fun entriesAt(level: Int): List<MenuEntry> {
        if (level == 0) return payload.entries
        var entries = payload.entries
        for (d in 0 until level) {
            // A level whose owner is no longer on the open chain has no rows; `opened` is only ever
            // trimmed down to `depth - 1`, but reading past it would be a crash rather than an empty
            // level, so the lookup stays total.
            val index = opened.getOrNull(d) ?: return emptyList()
            val row = entries.getOrNull(index) as? MenuEntry.Submenu ?: return emptyList()
            entries = row.children
        }
        return entries
    }

    fun highlightedAt(level: Int): Int = highlighted.getOrElse(level) { 0 }

    fun entryAt(level: Int, index: Int): MenuEntry? = entriesAt(level).getOrNull(index)

    /**
     * Moves the highlight to [index] in [level].
     *
     * Rows deeper than [level] close, because the pointer has demonstrably left them. If [index] is
     * the row that already owns the level below, that chain is left alone — entering a submenu through
     * its own parent must not rebuild it.
     */
    fun highlight(level: Int, index: Int, openImmediately: Boolean) {
        val ownsOpenChild = opened.getOrNull(level) == index && opened.size == level + 1
        if (!ownsOpenChild) {
            while (opened.size > level) {
                opened.removeAt(opened.size - 1)
            }
        }
        while (highlighted.size <= level) highlighted.add(0)
        if (highlighted.getOrElse(level) { 0 } != index) {
            while (highlighted.size > level + 1) highlighted.removeAt(highlighted.size - 1)
            highlighted[level] = index
        }
        activeLevel = level
        val row = entryAt(level, index)
        val shouldOpen = row is MenuEntry.Submenu && row.enabled
        when {
            !shouldOpen -> {
                pendingOpen = null
                publishDepth(level + 1)
            }

            opened.getOrNull(level) == index -> publishDepth(level + 2)

            openImmediately -> {
                if (opened.size <= level) opened.add(index) else opened[level] = index
                while (opened.size > level + 1) opened.removeAt(opened.size - 1)
                // The IDE lands on the first real row of the level it just opened, not on row zero
                // whatever that happens to be.
                val childRows = entriesAt(level + 1)
                while (highlighted.size <= level + 1) highlighted.add(0)
                highlighted[level + 1] = firstSelectable(childRows)
                pendingOpen = null
                publishDepth(level + 2)
            }

            else -> {
                // Waiting out the hover delay. The level that was here has just been closed, so the
                // published depth has to follow it — otherwise the surface keeps composing a level the
                // open chain no longer describes.
                publishDepth(level + 1)
                pendingOpen = level to index
            }
        }
    }

    /** Applies the hover-delayed open. Called by the surface once the delay has elapsed. */
    fun commitPendingOpen() {
        val (level, index) = pendingOpen ?: return
        pendingOpen = null
        highlight(level, index, openImmediately = true)
    }

    /** Drops the innermost level; returns false once nothing but the root is left. */
    fun closeInnermostLevel(): Boolean {
        if (opened.isEmpty()) return false
        opened.removeAt(opened.size - 1)
        if (highlighted.size > 1) highlighted.removeAt(highlighted.size - 1)
        if (activeLevel > opened.size) activeLevel = opened.size
        publishDepth(opened.size + 1)
        return opened.isNotEmpty()
    }

    private fun publishDepth(value: Int) {
        while (highlighted.size > value) highlighted.removeAt(highlighted.size - 1)
        while (highlighted.size < value) highlighted.add(0)
        depth = value
    }

    // ── pointer ─────────────────────────────────────────────────────────────

    /**
     * Routes the pointer to the deepest level that actually contains [position], with a small tolerance
     * around each level so the diagonal trip between a parent row and its submenu — and the few pixels
     * between two adjacent rows — does not read as "left the menu".
     */
    fun onPointerPosition(position: Offset) {
        for (level in depth - 1 downTo 0) {
            val bounds = _levelBounds[level] ?: continue
            if (position isIn bounds.withTolerance(TOLERANCE)) {
                if (!pointerInside) pointerInside = true
                val index = rowAt(level, position) ?: return
                highlight(level, index, openImmediately = false)
                return
            }
        }
        if (pointerInside) pointerInside = false
    }

    fun onPointerExit() {
        pointerInside = false
    }

    private fun rowAt(level: Int, position: Offset): Int? {
        val entries = entriesAt(level)
        for (index in entries.indices) {
            val bounds = _rowBounds[level to index] ?: continue
            if (position isIn bounds.withTolerance(TOLERANCE)) return index
        }
        return null
    }

    // ── keyboard ────────────────────────────────────────────────────────────

    /**
     * Handles one key press. [onDismiss] closes the whole menu — the one exit the model cannot perform
     * by itself, since dropping the last level is the owner's decision. Returns whether the press was
     * consumed, so an unclaimed key carries on past the menu exactly as it does in the IDE.
     */
    fun onKeyEvent(event: KeyEvent, onDismiss: () -> Unit): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val level = activeLevel.coerceIn(0, depth - 1)
        val entries = entriesAt(level)
        when (event.key) {
            // One Escape peels off the innermost level; the one from the root level closes the menu.
            Key.Escape -> {
                if (depth > 1) closeInnermostLevel() else onDismiss()
                return true
            }

            Key.DirectionUp -> moveHighlight(level, entries, backwards = true)
            Key.DirectionDown -> moveHighlight(level, entries, backwards = false)
            Key.MoveHome -> selectFirstEnabled(level, entries)
            Key.MoveEnd -> selectLastEnabled(level, entries)
            Key.DirectionRight -> {
                val row = entryAt(level, highlightedAt(level))
                if (row is MenuEntry.Submenu && row.enabled) highlight(level, highlightedAt(level), openImmediately = true)
            }

            Key.DirectionLeft -> if (depth > 1) closeInnermostLevel() else return false
            Key.Enter, Key.Spacebar -> {
                val row = entryAt(level, highlightedAt(level)) as? MenuEntry.Action ?: return true
                if (!row.enabled) return true
                row.onClick()
                // Activating a leaf closes every level, which is what the IDE does after a menu command.
                onDismiss()
                return true
            }

            else -> return false
        }
        return true
    }

    private fun moveHighlight(level: Int, entries: List<MenuEntry>, backwards: Boolean) {
        if (entries.isEmpty()) return
        val start = highlightedAt(level)
        var index = start
        entries.indices.forEach { _ ->
            index = if (backwards) (index - 1 + entries.size) % entries.size else (index + 1) % entries.size
            val row = entries[index]
            if (row !is MenuEntry.Separator && row.enabled) {
                highlight(level, index, openImmediately = false)
                return
            }
        }
    }

    private fun selectFirstEnabled(level: Int, entries: List<MenuEntry>) {
        val index = entries.indexOfFirst { it !is MenuEntry.Separator && it.enabled }
        if (index >= 0) highlight(level, index, openImmediately = false)
    }

    private fun selectLastEnabled(level: Int, entries: List<MenuEntry>) {
        val index = entries.indexOfLast { it !is MenuEntry.Separator && it.enabled }
        if (index >= 0) highlight(level, index, openImmediately = false)
    }

    private companion object {
        /** Hover tolerance, in px, applied to every measured level and row. */
        const val TOLERANCE = 12f
    }
}

private infix fun Offset.isIn(rect: Rect): Boolean = rect.contains(this)

private fun Rect.withTolerance(px: Float): Rect = if (this == Rect.Zero) this else inflate(px)

// ─────────────────────────────────────────────────────────────────────────────
// The surface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The IDE-quality popup menu Blaze draws in place of Jewel's `PopupMenu`.
 *
 * Why it is not built on Jewel's menu: Jewel drives its submenu state entirely from per-item hover,
 * and the moment a submenu popup exists the level beneath it stops receiving pointer events, so the
 * parent can neither close the submenu nor move its own highlight. The mouse gets stuck inside the
 * submenu and only the arrow keys get out — the exact bug reported. `BlazeMenu` puts every level in
 * one surface, observes the raw pointer there, and decides open/close/highlight from geometry, so no
 * state exists that the mouse cannot leave.
 *
 * Look comes entirely from the theme's menu tokens ([menuStyle]): corner radius, 1dp border, shadow,
 * row height, 6dp selection radius with its horizontal inset, 16dp icon column, separator thickness
 * and margins, chevron and item colors — nothing about the appearance is hard-coded here.
 *
 * @param anchor where to hang the root level (a control's bounds, or the point of a right-click).
 * @param inScene draw into the current composition instead of a window popup — used by the screenshot
 *   harness, which has no window to pop up into.
 * @param initialOpenSubmenu the root row to render with its submenu already open. Only the screenshot
 *   harness uses it: an offscreen scene has no pointer to hover the parent with, and the open state is
 *   otherwise reachable exclusively through hover, click and the right arrow.
 */
@Composable
fun BlazeMenu(
    entries: List<MenuEntry>,
    anchor: MenuAnchor,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    alignRootToEnd: Boolean = false,
    inScene: Boolean = false,
    initialOpenSubmenu: Int? = null,
    headerBounds: Map<out Any, Rect> = emptyMap(),
    onHeaderHovered: ((Any) -> Unit)? = null,
    onHeaderClicked: ((Any) -> Unit)? = null,
) {
    val model = remember { BlazeMenuModel(entries) }
    val style = JewelTheme.menuStyle
    // Refresh the rows on every recomposition of the owner; the payload's shape decides whether that
    // costs a frame, so a menu sitting open over a downloading row keeps its hover state.
    model.submit(entries)

    LaunchedEffect(model) { model.highlight(0, firstSelectable(entries), openImmediately = false) }

    // Declared after the root highlight above so it wins: seeding is what the harness asks for.
    LaunchedEffect(model, initialOpenSubmenu) {
        val index = initialOpenSubmenu ?: return@LaunchedEffect
        model.highlight(0, index, openImmediately = true)
    }

    // The grace period that collapses an open submenu once the pointer has left every level, and the
    // hover delay that opens one it is resting on. Both are driven by the same model flags, so moving
    // the pointer back cancels whichever timer is running.
    LaunchedEffect(model.pointerInside, model.depth) {
        if (!model.pointerInside) {
            delay(CollapseDelayMs.milliseconds)
            while (model.closeInnermostLevel()) delay(CollapseDelayMs.milliseconds)
        }
    }
    LaunchedEffect(model.pendingOpen) {
        val target = model.pendingOpen ?: return@LaunchedEffect
        delay(SubmenuOpenDelayMs.milliseconds)
        if (model.pendingOpen == target) model.commitPendingOpen()
    }

    val content: @Composable (Modifier) -> Unit = { surfaceModifier ->
        MenuSurface(
            model = model,
            style = style,
            anchor = anchor.bounds,
            onDismissRequest = onDismissRequest,
            alignRootToEnd = alignRootToEnd,
            modifier = surfaceModifier,
            headerBounds = headerBounds,
            onHeaderHovered = onHeaderHovered,
            onHeaderClicked = onHeaderClicked,
        )
    }

    if (inScene) {
        content(modifier)
    } else {
        Popup(
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            popupPositionProvider = FullWindowPositionProvider,
            onKeyEvent = { model.onKeyEvent(it, onDismissRequest) },
        ) {
            content(modifier)
        }
    }
}

private const val SubmenuOpenDelayMs = 300L
private const val CollapseDelayMs = 260L

private fun firstSelectable(entries: List<MenuEntry>): Int {
    val index = entries.indexOfFirst { it !is MenuEntry.Separator && it.enabled }
    return if (index >= 0) index else 0
}

/** One transparent layer covering the window, so every pointer position is observed by the surface. */
private object FullWindowPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

@Composable
private fun MenuSurface(
    model: BlazeMenuModel,
    style: MenuStyle,
    anchor: Rect,
    onDismissRequest: () -> Unit,
    alignRootToEnd: Boolean,
    modifier: Modifier = Modifier,
    headerBounds: Map<out Any, Rect> = emptyMap(),
    onHeaderHovered: ((Any) -> Unit)? = null,
    onHeaderClicked: ((Any) -> Unit)? = null,
) {
    val density = LocalDensity.current
    var containerSize by remember { mutableStateOf(Size.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { containerSize = it.size.let { s -> Size(s.width.toFloat(), s.height.toFloat()) } }
            .pointerInput(model, onDismissRequest, headerBounds) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter, PointerEventType.Exit -> {
                                val position = event.changes.firstOrNull()?.position ?: continue
                                if (event.type == PointerEventType.Exit) {
                                    // The Exit position is still inside the level being left; treat the
                                    // loss of the pointer as "outside" and let the grace timer decide.
                                    model.onPointerExit()
                                } else {
                                    var overHeader = false
                                    for ((key, bounds) in headerBounds) {
                                        if (bounds.contains(position)) {
                                            overHeader = true
                                            onHeaderHovered?.invoke(key)
                                            break
                                        }
                                    }
                                    if (!overHeader) {
                                        model.onPointerPosition(position)
                                    }
                                }
                            }

                            PointerEventType.Release -> {
                                val position = event.changes.firstOrNull()?.position ?: continue
                                var clickedHeader = false
                                for ((key, bounds) in headerBounds) {
                                    if (bounds.contains(position)) {
                                        clickedHeader = true
                                        onHeaderClicked?.invoke(key)
                                        break
                                    }
                                }
                                if (!clickedHeader) {
                                    if (!activateAt(model, position)) onDismissRequest()
                                }
                            }

                            else -> Unit
                        }
                    }
                }
            },
    ) {
        val submenuOffsetPx = with(density) { style.metrics.submenuMetrics.offset.x.roundToPx().toFloat() }
        val rootGapPx = with(density) { style.metrics.offset.y.roundToPx().toFloat() }
        for (level in 0 until model.depth) {
            MenuLevel(
                model = model,
                style = style,
                level = level,
                anchor = anchor,
                containerSize = containerSize,
                submenuOffsetPx = submenuOffsetPx,
                rootGapPx = rootGapPx,
                alignRootToEnd = alignRootToEnd,
            )
        }
    }
}

/**
 * Press handling: the innermost level wins, a submenu row expands at once, a leaf fires and closes
 * everything, and a press outside every level dismisses — which is how "click outside closes all
 * levels" behaves.
 */
private fun activateAt(model: BlazeMenuModel, position: Offset): Boolean {
    for (level in model.depth - 1 downTo 0) {
        val bounds = model.levelBounds(level) ?: continue
        if (!bounds.contains(position)) continue
        val entries = model.entriesAt(level)
        val index = entries.indices.firstOrNull { model.rowContains(level, it, position) }
            ?: return true
        return model.activateRow(level, index)
    }
    return false
}

@Composable
private fun MenuLevel(
    model: BlazeMenuModel,
    style: MenuStyle,
    level: Int,
    anchor: Rect,
    containerSize: Size,
    submenuOffsetPx: Float,
    rootGapPx: Float,
    alignRootToEnd: Boolean,
) {
    val entries = model.entriesAt(level)
    if (entries.isEmpty()) return

    var bounds by remember { mutableStateOf(Rect.Zero) }
    val offset = levelOffset(
        parent = if (level == 0) anchor else model.parentRowBounds(level) ?: anchor,
        own = bounds,
        container = containerSize,
        isRoot = level == 0,
        alignRootToEnd = alignRootToEnd,
        submenuOffsetPx = submenuOffsetPx,
        rootGapPx = rootGapPx,
    )

    val shape = RoundedCornerShape(style.metrics.cornerSize)
    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .shadow(
                elevation = style.metrics.shadowSize,
                shape = shape,
                clip = false,
                ambientColor = style.colors.shadow,
                spotColor = style.colors.shadow,
            )
            .background(style.colors.background, shape)
            .border(style.metrics.borderWidth, style.colors.border, shape)
            .onGloballyPositioned { coords ->
                val rect = coords.boundsInRoot()
                bounds = rect
                model.onLevelBounds(level, rect)
            }
            .padding(style.metrics.contentPadding),
    ) {
        val showIconColumn = entries.any { entry ->
            when (entry) {
                is MenuEntry.Action -> entry.icon != null || entry.checked != null
                is MenuEntry.Submenu -> entry.icon != null
                MenuEntry.Separator -> false
            }
        }
        // One level is exactly as wide as its widest row, which is what makes the surface's hit test
        // mean anything: without it every level would fill the window, the innermost would swallow
        // every pointer position, and the parent menu could never be reached again. `IntrinsicSize.Max`
        // is the standard way to give a group of `fillMaxWidth` rows one shared natural width.
        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            entries.forEachIndexed { index, entry ->
                when (entry) {
                    MenuEntry.Separator -> MenuSeparator(style)
                    else -> MenuRow(
                        style = style,
                        entry = entry,
                        highlighted = model.highlightedAt(level) == index,
                        showIconColumn = showIconColumn,
                        onBounds = { model.onRowBounds(level, index, it) },
                    )
                }
            }
        }
    }
}

/**
 * Places one level against the row it hangs off, then keeps it on screen: a submenu that would run
 * past the right edge opens to the left of its parent instead, and any level that would run past the
 * bottom shifts up. Measured sizes arrive one frame after placement, so the first frame is the ideal
 * position and the next settles it — invisible for a menu, whose rows never resize themselves.
 */
private fun levelOffset(
    parent: Rect,
    own: Rect,
    container: Size,
    isRoot: Boolean,
    alignRootToEnd: Boolean,
    submenuOffsetPx: Float,
    rootGapPx: Float,
): Offset {
    if (container == Size.Zero) return Offset(parent.left, parent.bottom)
    val width = if (own.width > 0f) own.width else 0f
    val height = if (own.height > 0f) own.height else 0f

    var x: Float
    var y: Float
    if (isRoot) {
        // A row's context menu hangs off the cursor; a header or tool-window menu lines its right
        // edge up with the control that opened it, the way the IDE's own menus do.
        x = if (alignRootToEnd) parent.right - width else parent.left
        y = parent.bottom + rootGapPx
        if (x + width > container.width) x = parent.right - width
        if (y + height > container.height) y = parent.top - height - rootGapPx
    } else {
        x = parent.right + submenuOffsetPx
        y = parent.top
        if (x + width > container.width) x = (parent.left - submenuOffsetPx - width).coerceAtLeast(0f)
        if (y + height > container.height) y = (container.height - height).coerceAtLeast(0f)
    }
    return Offset(x.coerceIn(0f, (container.width - width).coerceAtLeast(0f)), y.coerceAtLeast(0f))
}

/** 1dp of separator color inside the theme's own vertical margins. */
@Composable
private fun MenuSeparator(style: MenuStyle) {
    val metrics = style.metrics.itemMetrics
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(metrics.separatorPadding)
            .height(metrics.separatorThickness)
            .background(style.colors.itemColors.separator),
    )
}

/**
 * One row: the fixed icon column, the label at regular weight, and the trailing accelerator and
 * chevron. Input is deliberately absent — the surface owns pointer and key events, which is what
 * makes the submenu behavior survivable.
 */
@Composable
private fun MenuRow(
    style: MenuStyle,
    entry: MenuEntry,
    /** Whether this is the row the pointer or the keyboard is on. Named apart from the `Selected`
     * semantics property it publishes, which the lambda below would otherwise shadow. */
    highlighted: Boolean,
    showIconColumn: Boolean,
    onBounds: (Rect) -> Unit,
) {
    val metrics = style.metrics.itemMetrics
    val itemColors = style.colors.itemColors
    val enabled = entry.enabled

    val background = when {
        !enabled -> itemColors.backgroundDisabled
        highlighted -> itemColors.backgroundHovered
        else -> Color.Transparent
    }
    val contentColor = when {
        !enabled -> itemColors.contentDisabled
        highlighted -> itemColors.contentHovered
        else -> itemColors.content
    }
    val iconColor = when {
        !enabled -> itemColors.iconTintDisabled
        highlighted -> itemColors.iconTintHovered
        else -> itemColors.iconTint
    }
    val keybindingColor = when {
        !enabled -> itemColors.keybindingTintDisabled
        highlighted -> itemColors.keybindingTintHovered
        else -> itemColors.keybindingTint
    }
    val action = entry as? MenuEntry.Action
    val submenu = entry as? MenuEntry.Submenu
    val accelerator = action?.accelerator ?: submenu?.accelerator

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = metrics.minHeight)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            // The row the keyboard/pointer is on is the row a screen reader should read as selected;
            // publishing it as real semantics is also what lets the UI tests assert where the highlight
            // actually went rather than guessing from the drawing. Descendants merge so the row itself
            // — not only its label — is the node that carries both the text and the selection.
            .semantics(mergeDescendants = true) { selected = highlighted }
            .padding(metrics.outerPadding)
            .clip(RoundedCornerShape(metrics.selectionCornerSize))
            .background(background)
            .padding(metrics.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showIconColumn) {
            val mark = action?.checked
            when {
                mark == true -> Icon(
                    key = AllIconsKeys.Actions.Checked,
                    contentDescription = null,
                    modifier = Modifier.size(metrics.iconSize),
                    tint = iconColor,
                )

                action?.icon != null -> Icon(
                    key = action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(metrics.iconSize),
                    tint = iconColor,
                )

                submenu?.icon != null -> Icon(
                    key = submenu.icon,
                    contentDescription = null,
                    modifier = Modifier.size(metrics.iconSize),
                    tint = iconColor,
                )

                // The column is reserved even when this row has nothing to draw, so labels in one
                // menu always start at the same x — the alignment the reference keeps.
                else -> Spacer(Modifier.size(metrics.iconSize))
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = JewelTheme.typography.regular,
                color = contentColor,
                maxLines = 1,
            )
        }
        if (accelerator != null) {
            Text(
                text = accelerator,
                style = JewelTheme.typography.small,
                color = keybindingColor,
                modifier = Modifier.padding(metrics.keybindingsPadding),
                maxLines = 1,
            )
        }

        if (submenu != null) {
            Icon(
                key = style.icons.submenuChevron,
                contentDescription = null,
                modifier = Modifier.size(metrics.iconSize),
                tint = iconColor,
            )
        }
    }
}
