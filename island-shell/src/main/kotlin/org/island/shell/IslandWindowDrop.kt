package org.island.shell

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.island.theme.ui.IslandSemanticColors
import org.island.ui.text.LocalIslandUiStrings
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File

/**
 * The shell's classification result for one raw dropped value. The optional [label] is what the
 * hover overlay shows; when omitted the shell falls back to its own shortening of the raw value
 * (file name for a path, truncated URL for a link, "magnet:..." prefix for a magnet). The
 * [payload] is the app's own typed request. The shell never inspects the payload - the host casts
 * it back out inside [onDropOutcome] and submits it wherever it needs to (Blaze feeds its
 * external-open inbox; a demo app can just log).
 */
class IslandDropIntent(val payload: Any?, val label: String? = null)

/** A capability hint row shown in the drop overlay when nothing classifies (or the peek failed). */
data class IslandDropHint(val text: String, val iconKey: IconKey)

/**
 * The state the drop overlay renders from, plus the [Modifier] the shell hangs on the window's
 * content root so the whole frame accepts drops. A host creates one through
 * [rememberIslandWindowDrop] and hands it to [IslandWindow]; the shell draws [IslandDropOverlay]
 * over the body itself, so the drop feedback lives entirely inside the shell.
 *
 * Use [IslandWindowDrop.None] for a window that should not accept drops at all.
 */
class IslandWindowDrop(
    val modifier: Modifier,
    val dragInProgress: Boolean,
    val supported: Boolean,
    val itemNames: List<String>,
    val hiddenCount: Int,
    val hints: List<IslandDropHint>,
) {
    companion object {
        /** Cap on hovered names shown in the overlay; the rest collapses into "+N more". */
        const val MaxPreviewNames = 5

        /** Sentinel drop state for windows that never show the overlay or accept a drop. */
        val None: IslandWindowDrop = IslandWindowDrop(
            modifier = Modifier,
            dragInProgress = false,
            supported = true,
            itemNames = emptyList(),
            hiddenCount = 0,
            hints = emptyList(),
        )
    }
}

/**
 * Whole-window drop support built on Compose's own drag-and-drop plumbing.
 *
 * A hand-rolled AWT `DropTarget` on the outer [java.awt.Frame] does *not* work here: on Linux the
 * XDND drop events are delivered to the window under the pointer - the heavyweight surface hosting
 * the Compose scene - and the frame-level target never fires. `Modifier.dragAndDropTarget` is the
 * supported path: Compose's desktop `AwtDragAndDropManager` installs the [java.awt.dnd.DropTarget]
 * on the correct root container itself and routes hits through the Compose hierarchy, so the
 * modifier attached at the window's content root receives drops over every child.
 *
 * The shell extracts raw string values from the payload (file paths for a file list, one entry per
 * line for text) and hands each to the app's [classify] callback - the same parser the app runs on
 * argv and IPC, so a drop cannot bypass the app's own validation. On drop the shell reports the
 * split outcome to [onDropOutcome] and consumes the gesture exactly when that callback says the
 * app accepted something.
 *
 * While a drag hovers, the target *peeks* the payload (file names / dropped text) so the overlay
 * can pre-announce acceptance. The peek is best-effort: some sources only hand over data at drop
 * time, and a failed peek falls back to the generic capability list rather than falsely rejecting.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun rememberIslandWindowDrop(
    classify: (String) -> IslandDropIntent?,
    onDropOutcome: (accepted: List<IslandDropIntent>, rejectedNames: List<String>) -> Boolean,
    hints: List<IslandDropHint> = emptyList(),
): IslandWindowDrop {
    var dragging by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf(true) }
    var previewNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var hiddenCount by remember { mutableStateOf(0) }

    val target = remember(classify, onDropOutcome) {
        object : DragAndDropTarget {
            // Per-session cache so the dragOver flood re-reads the payload at most once.
            private var peeked: Transferable? = null
            private var verdict: HoverVerdict = HoverVerdict(emptyList(), 0, true)

            private fun recompute(transferable: Transferable?) {
                if (transferable == null) return
                if (peeked === transferable) return // one peek per session is enough
                peeked = transferable
                verdict = peekHover(transferable, classify)
            }

            override fun onStarted(event: DragAndDropEvent) {
                peeked = null
                recompute((event.nativeEvent as? DropTargetDragEvent)?.transferable)
                dragging = true
                supported = verdict.supported
                previewNames = verdict.names
                hiddenCount = verdict.hidden
            }

            override fun onEntered(event: DragAndDropEvent) = onStarted(event)

            override fun onMoved(event: DragAndDropEvent) {
                // Some toolkits deliver the flavor data late; keep trying until a peek succeeds.
                if (peeked == null) recompute((event.nativeEvent as? DropTargetDragEvent)?.transferable)
                if (peeked != null) {
                    supported = verdict.supported
                    previewNames = verdict.names
                    hiddenCount = verdict.hidden
                }
            }

            override fun onExited(event: DragAndDropEvent) {
                dragging = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                dragging = false
                peeked = null
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                peeked = null
                // Compose's manager has already called acceptDrop/dropComplete around this
                // callback - the return value alone tells the platform whether the drop counted.
                val transferable = (event.nativeEvent as? DropTargetDropEvent)?.transferable
                    ?: return false
                val values = extractDroppedValues(transferable)
                // One classification pass per value: the app's parser alone decides what is usable.
                val classified = values.associateWith(classify)
                val accepted = classified.values.filterNotNull()
                val rejectedNames = classified.filterValues { it == null }.keys.map(::shortDropLabel)

                // Consume the gesture when anything usable landed; a fully unusable drop bounces
                // back to the source (the balloon the app reports above explains why).
                return onDropOutcome(accepted, rejectedNames)
            }
        }
    }

    val modifier = remember(target) {
        Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                // Start a session for any payload carrying files or text - even one the app will
                // refuse - so the overlay can *show* why the drop is unsupported instead of the OS
                // silently ignoring the gesture.
                (event.nativeEvent as? DropTargetDragEvent)
                    ?.transferable
                    ?.hasReadablePayload() == true
            },
            target = target,
        )
    }

    return IslandWindowDrop(
        modifier = modifier,
        dragInProgress = dragging,
        supported = supported,
        itemNames = previewNames,
        hiddenCount = hiddenCount,
        hints = hints,
    )
}

/** What a hover peek found: up to [IslandWindowDrop.MaxPreviewNames] display names, the overflow count, and the verdict. */
private class HoverVerdict(val names: List<String>, val hidden: Int, val supported: Boolean)

/**
 * Best-effort hover peek: lists the dragged file names (or dropped text entries) and judges each
 * through the app's classifier so the overlay can pre-announce acceptance. Reading transfer data
 * mid-drag is not guaranteed to succeed - a failure never downgrades the verdict, because the
 * drop path classifies for real and reports the truth there.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun peekHover(transferable: Transferable, classify: (String) -> IslandDropIntent?): HoverVerdict = runCatching {
    val values = extractDroppedValues(transferable)
    if (values.isEmpty()) return HoverVerdict(emptyList(), 0, true)
    var allSupported = true
    val names = mutableListOf<String>()
    for (value in values) {
        val intent = classify(value)
        if (intent == null) allSupported = false
        names += intent?.label ?: shortDropLabel(value)
    }
    val hidden = (names.size - IslandWindowDrop.MaxPreviewNames).coerceAtLeast(0)
    HoverVerdict(names.take(IslandWindowDrop.MaxPreviewNames), hidden, allSupported)
}.getOrDefault(HoverVerdict(emptyList(), 0, true))

/** File lists (managers, OS shells) and any text-ish flavor (browsers: uri-list / plain text). */
private fun Transferable.hasReadablePayload(): Boolean = runCatching {
    transferDataFlavors.any { it == DataFlavor.javaFileListFlavor || it.isFlavorTextType }
}.getOrDefault(false)

/**
 * Pulls every raw string out of a drop or hover: file paths for a dropped file list, one entry per
 * line for dropped text (browsers hand over newline-separated URL lists). The *contents* of dropped
 * files are never read here - only paths/lines are extracted and handed to the app's classifier.
 */
private fun extractDroppedValues(transferable: Transferable): List<String> = runCatching {
    val values = mutableListOf<String>()
    when {
        transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                ?.filterIsInstance<File>()
                ?.forEach { values += it.absolutePath }

        transferable.isDataFlavorSupported(DataFlavor.stringFlavor) ->
            (transferable.getTransferData(DataFlavor.stringFlavor) as? String)?.lineSequence()?.map(String::trim)
                ?.filter(String::isNotEmpty)?.forEach(values::add)

        else -> {
            // Fallback for exotic text flavors (e.g. a raw text/uri-list not auto-converted).
            val flavor = transferable.transferDataFlavors.firstOrNull { it.isFlavorTextType }
            (flavor?.let(transferable::getTransferData) as? String)?.lineSequence()?.map(String::trim)
                ?.filter(String::isNotEmpty)?.forEach(values::add)
        }
    }
    values
}.getOrDefault(emptyList())

/** A name for long text is meaningless in an overlay - keep links short, paths to their file name. */
private fun shortDropLabel(value: String): String = when {
    value.startsWith("magnet:") -> "magnet:" + value.removePrefix("magnet:").take(12) + "…"
    value.contains("://") -> value.take(48) + if (value.length > 48) "…" else ""
    else -> runCatching { File(value).name }.getOrDefault(value)
}

/**
 * The hint layer shown while a drag hovers the window. Pure feedback: the drop is received by the
 * [dragAndDropTarget][androidx.compose.foundation.draganddrop.dragAndDropTarget] modifier on the
 * content root beneath this layer, and the overlay never registers as a target of its own, so it
 * can't steal the gesture. Accent-styled when the payload classifies, error-styled when it does
 * not, and it always shows *what* is being dragged so multi-file drops are confirmable mid-flight.
 */
@Composable
fun IslandDropOverlay(drop: IslandWindowDrop, modifier: Modifier = Modifier) {
    val strings = LocalIslandUiStrings.current
    AnimatedVisibility(
        visible = drop.dragInProgress,
        enter = fadeIn(tween(120)) + scaleIn(tween(150), initialScale = 0.985f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.985f),
    ) {
        val accent = if (drop.supported) IslandSemanticColors.accent else IslandSemanticColors.error
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(accent.copy(alpha = 0.07f))
                .border(3.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.96f))
                    .border(2.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        key = if (drop.supported) AllIconsKeys.Actions.Download else AllIconsKeys.General.Error,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = if (drop.supported) strings.dropOverlayTitle()
                        else strings.dropOverlayUnsupportedTitle(),
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                        color = if (drop.supported) Color.Unspecified else IslandSemanticColors.error,
                    )
                }

                // Named preview while we know what's in flight; generic capability list otherwise.
                if (drop.supported && drop.itemNames.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        drop.itemNames.forEach { name ->
                            Text(
                                text = name,
                                style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (drop.hiddenCount > 0) {
                            Text(
                                text = strings.dropItemsMore(drop.hiddenCount),
                                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                                color = JewelTheme.globalColors.text.info,
                            )
                        }
                    }
                } else if (drop.hints.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        drop.hints.forEach { hint -> DropHintRow(hint) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DropHintRow(hint: IslandDropHint) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            key = hint.iconKey,
            contentDescription = null,
            tint = IslandSemanticColors.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = hint.text,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
            color = JewelTheme.globalColors.text.info,
        )
    }
}
