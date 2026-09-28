package org.blaze.presentation.application.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.osintegration.ExternalOpenRequest
import org.blaze.osintegration.classifyForDropOrNull
import org.blaze.presentation.components.notifications.NotificationsState
import org.blaze.presentation.theme.BlazeColors
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
 * Whole-window drop support built on Compose's own drag-and-drop plumbing.
 *
 * A hand-rolled AWT `DropTarget` on the outer [java.awt.Frame] does *not* work here: on Linux the
 * XDND drop events are delivered to the window under the pointer - the heavyweight surface hosting
 * the Compose scene - and the frame-level target never fires. `Modifier.dragAndDropTarget` is the
 * supported path: Compose's desktop `AwtDragAndDropManager` installs the [java.awt.dnd.DropTarget]
 * on the correct root container itself and routes hits through the Compose hierarchy, so the
 * modifier attached at the window's content root receives drops over every child.
 *
 * Every hovered item and every drop is classified through the shared [ExternalOpenParser] - the
 * same validation boundary as argv and the single-instance IPC channel, with the full drop
 * vocabulary: magnets, web links, `.torrent` files, link-list text files and candidate partial
 * downloads (any existing file/folder whose portability the engine decides later). Accepted
 * requests land in the [ExternalOpenInbox]; from there the existing external-open flow takes
 * over. This surface never talks to the engine directly, so a drop can never bypass the dialog's
 * destination/conflict checks.
 *
 * While a drag hovers, the target *peeks* the payload (file names / dropped text) so the overlay
 * can promise "Drop to add downloads" only for payloads that classify, and warn for the ones that
 * cannot. The peek is best-effort: some sources only hand over data at drop time, and a failed
 * peek falls back to the generic promise rather than falsely rejecting.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun rememberWindowDropTarget(
    inbox: ExternalOpenInbox,
    parser: ExternalOpenParser,
    notifications: NotificationsState,
): WindowDrop {
    var dragging by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf(true) }
    var previewNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var hiddenCount by remember { mutableStateOf(0) }
    val strings = blazeStrings

    val target = remember(inbox, parser, notifications, strings) {
        object : DragAndDropTarget {
            // Per-session cache so the dragOver flood re-reads the payload at most once.
            private var peeked: Transferable? = null
            private var verdict: HoverVerdict = HoverVerdict(emptyList(), 0, true)

            private fun recompute(transferable: Transferable?) {
                if (transferable == null) return
                if (peeked === transferable) return // one peek per session is enough
                peeked = transferable
                verdict = peekHover(transferable, parser)
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
                // One classification pass per value: the parser alone decides what is usable.
                val classified = values.associateWith(parser::classifyForDropOrNull)
                val requests = classified.values.filterNotNull()
                val rejectedNames = classified.filterValues { it == null }.keys.map(::shortDropLabel)

                if (requests.isNotEmpty()) inbox.submit(requests)
                reportDropOutcome(strings, notifications, requests.size, rejectedNames)

                // Consume the gesture when anything usable landed; a fully unusable drop bounces
                // back to the source (the balloon above explains why).
                return requests.isNotEmpty()
            }
        }
    }

    val modifier = remember(target) {
        Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                // Start a session for any payload carrying files or text - even one Blaze will
                // refuse - so the overlay can *show* why the drop is unsupported instead of the
                // OS silently ignoring the gesture.
                (event.nativeEvent as? DropTargetDragEvent)
                    ?.transferable
                    ?.hasReadablePayload() == true
            },
            target = target,
        )
    }

    return WindowDrop(
        modifier = modifier,
        dragInProgress = dragging,
        supported = supported,
        itemNames = previewNames,
        hiddenCount = hiddenCount,
    )
}

/** What a hover peek found: up to [WindowDrop.MaxPreviewNames] display names, the overflow count, and the verdict. */
private class HoverVerdict(val names: List<String>, val hidden: Int, val supported: Boolean)

/** The modifier to hang on the window content root, plus everything the overlay renders from. */
class WindowDrop(
    val modifier: Modifier,
    val dragInProgress: Boolean,
    val supported: Boolean,
    val itemNames: List<String>,
    val hiddenCount: Int,
) {
    companion object {
        /** Cap on hovered names shown in the overlay; the rest collapses into "+N more". */
        const val MaxPreviewNames = 5
    }
}

/**
 * Best-effort hover peek: lists the dragged file names (or dropped text entries) and judges each
 * through the drop classifier so the overlay can pre-announce acceptance. Reading transfer data
 * mid-drag is not guaranteed to succeed - a failure never downgrades the verdict, because the
 * drop path classifies for real and reports the truth there.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun peekHover(transferable: Transferable, parser: ExternalOpenParser): HoverVerdict = runCatching {
    val values = extractDroppedValues(transferable)
    if (values.isEmpty()) return HoverVerdict(emptyList(), 0, true)
    var allSupported = true
    val names = mutableListOf<String>()
    for (value in values) {
        val request: ExternalOpenRequest? = parser.classifyForDropOrNull(value)
        if (request == null) allSupported = false
        names += shortDropLabel(value)
    }
    val hidden = (names.size - WindowDrop.MaxPreviewNames).coerceAtLeast(0)
    HoverVerdict(names.take(WindowDrop.MaxPreviewNames), hidden, allSupported)
}.getOrDefault(HoverVerdict(emptyList(), 0, true))

/** File lists (managers, OS shells) and any text-ish flavor (browsers: uri-list / plain text). */
private fun Transferable.hasReadablePayload(): Boolean = runCatching {
    transferDataFlavors.any { it == DataFlavor.javaFileListFlavor || it.isFlavorTextType }
}.getOrDefault(false)

/**
 * Pulls every raw string out of a drop or hover: file paths for a dropped file list, one entry per
 * line for dropped text (browsers hand over newline-separated URL lists). The *contents* of dropped
 * files are never read here - only paths/lines are extracted and handed to the parser, which alone
 * decides whether a path is a torrent, a link list or a resumable partial download.
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

/** Post-drop balloons: what got queued, and by name what could not be used. */
private fun reportDropOutcome(
    strings: BlazeStrings,
    notifications: NotificationsState,
    queued: Int,
    rejectedNames: List<String>,
) {
    val rejected = rejectedNames.joinToString(", ") { truncateName(it) }
    when {
        queued > 0 && rejectedNames.isEmpty() ->
            notifications.info(strings.downloads.drop.queued(queued))

        queued > 0 -> {
            notifications.info(strings.downloads.drop.queued(queued))
            notifications.warning(strings.downloads.drop.rejected(rejected))
        }

        rejectedNames.isNotEmpty() ->
            notifications.error(strings.downloads.drop.rejected(rejected))

        // A payload we could not read any item from (broken flavor data, unreadable list).
        else -> notifications.error(strings.downloads.drop.nothingUsable)
    }
}

private fun truncateName(label: String): String =
    if (label.length <= 32) label else label.take(30) + "…"

/**
 * The hint layer shown while a drag hovers the window. Pure feedback: the drop is received by the
 * [dragAndDropTarget][androidx.compose.foundation.draganddrop.dragAndDropTarget] modifier on the
 * content root beneath this layer, and the overlay never registers as a target of its own, so it
 * can't steal the gesture. Accent-styled when the payload classifies, error-styled when it does
 * not, and it always shows *what* is being dragged so multi-file drops are confirmable mid-flight.
 */
@Composable
fun WindowDropOverlay(drop: WindowDrop, modifier: Modifier = Modifier) {
    val strings = blazeStrings
    AnimatedVisibility(
        visible = drop.dragInProgress,
        enter = fadeIn(tween(120)) + scaleIn(tween(150), initialScale = 0.985f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.985f),
    ) {
        val accent = if (drop.supported) BlazeColors.accent else BlazeColors.error
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
                        text = if (drop.supported) strings.downloads.drop.overlayTitle
                        else strings.downloads.drop.overlayUnsupportedTitle,
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                        color = if (drop.supported) Color.Unspecified else BlazeColors.error,
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
                                text = strings.downloads.drop.itemsMore(drop.hiddenCount),
                                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                                color = JewelTheme.globalColors.text.info,
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DropHint(strings.downloads.drop.hintTorrents, AllIconsKeys.Nodes.Folder)
                        DropHint(strings.downloads.drop.hintLinks, AllIconsKeys.Actions.Download)
                        DropHint(strings.downloads.drop.hintLinkLists, AllIconsKeys.FileTypes.Any_type)
                        DropHint(strings.downloads.drop.hintPartials, AllIconsKeys.FileTypes.Archive)
                    }
                }
            }
        }
    }
}

@Composable
private fun DropHint(text: String, key: IconKey) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            key = key,
            contentDescription = null,
            tint = BlazeColors.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
            color = JewelTheme.globalColors.text.info,
        )
    }
}
