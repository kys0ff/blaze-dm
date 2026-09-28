package org.blaze.presentation.application.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.osintegration.classifyForDropOrNull
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.Frame
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.DropTargetListener
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * Attaches an AWT [DropTarget] to the decorated window so magnet links, web URLs and `.torrent`
 * files dragged from a browser or the file manager can be dropped anywhere onto Blaze. AWT
 * resolves a drop against the nearest [DropTarget] in the component hierarchy and then walks up
 * to its ancestors, so a target installed on the *frame* receives drops over every child -
 * including the whole Compose surface - without any per-widget wiring.
 *
 * Dropped payloads are classified through the shared [ExternalOpenParser] - the same validation
 * boundary as argv and the single-instance IPC channel, widened with plain web links - and land
 * in the [ExternalOpenInbox]. From there the existing external-open flow takes over: the window
 * raises and the add-download dialog pre-fills the source. This surface never talks to the
 * engine directly, so a drop can never bypass the dialog's destination/conflict checks.
 *
 * Returns whether a usable drag is currently over the window, so the caller can render a
 * [WindowDropOverlay] hint; the flag is driven from AWT callbacks on the event dispatch thread,
 * which the Compose snapshot system accepts as-is.
 */
@Composable
fun rememberWindowDropTarget(
    window: Frame,
    inbox: ExternalOpenInbox,
    parser: ExternalOpenParser,
): Boolean {
    var dragging by remember(window) { mutableStateOf(false) }

    DisposableEffect(window, inbox, parser) {
        val listener = object : DropTargetListener {
            override fun dragEnter(e: DropTargetDragEvent) {
                if (e.hasUsableFlavor()) dragging = true
            }

            // Some toolkits skip dragEnter when the drag starts inside the same top-level, and a
            // flavor table negotiated late can arrive empty at enter time - so re-check on move.
            override fun dragOver(e: DropTargetDragEvent) {
                if (!dragging && e.hasUsableFlavor()) dragging = true
            }

            override fun dragExit(e: DropTargetEvent) {
                dragging = false
            }

            // The source changed the offered gesture (e.g. Ctrl toggles copy/link); our acceptance
            // does not depend on the action, so only the hover flag needs re-evaluating.
            override fun dropActionChanged(e: DropTargetDragEvent) {
                dragging = e.hasUsableFlavor()
            }

            override fun drop(e: DropTargetDropEvent) {
                dragging = false
                val requests = extractDroppedValues(e).mapNotNull(parser::classifyForDropOrNull)
                if (requests.isNotEmpty()) inbox.submit(requests)
                e.dropComplete(requests.isNotEmpty())
            }
        }
        // autoDnD = true: AWT accepts the drag gesture on our behalf so hover feedback works even
        // for sources that only advertise COPY-or-LINK; the drop itself is completed manually.
        val target = DropTarget(window, DnDConstants.ACTION_COPY_OR_MOVE, listener, true)
        window.dropTarget = target
        onDispose {
            window.dropTarget = null
            dragging = false
        }
    }

    return dragging
}

/** File lists (managers, OS shells) and any text-ish flavor (browsers: uri-list / plain text). */
private fun DropTargetDragEvent.hasUsableFlavor(): Boolean =
    currentDataFlavors.any { it == DataFlavor.javaFileListFlavor || it.isFlavorTextType }

/**
 * Pulls every raw string out of a drop: file paths for a dropped file list, one entry per line for
 * dropped text (browsers hand over newline-separated URL lists). The *contents* of dropped files
 * are never read here - only paths are extracted and handed to the parser, which alone decides
 * whether a path is a usable `.torrent`.
 */
private fun extractDroppedValues(e: DropTargetDropEvent): List<String> = runCatching {
    e.acceptDrop(DnDConstants.ACTION_COPY)
    val transferable = e.transferable
    val values = mutableListOf<String>()
    when {
        transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                ?.filterIsInstance<File>()
                ?.forEach { values += it.absolutePath }

        transferable.isDataFlavorSupported(DataFlavor.stringFlavor) ->
            (transferable.getTransferData(DataFlavor.stringFlavor) as? String)
                ?.let { text ->
                    text.lineSequence()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach(values::add)
                }

        else -> {
            // Fallback for exotic text flavors (e.g. a raw text/uri-list not auto-converted).
            val flavor = transferable.transferDataFlavors.firstOrNull { it.isFlavorTextType }
            (flavor?.let(transferable::getTransferData) as? String)?.let { text ->
                text.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(values::add)
            }
        }
    }
    values
}.getOrDefault(emptyList())

/**
 * The translucent hint layer shown while a usable drag hovers the window. Pure feedback: it is
 * drawn over the content, never intercepts input (drops land on the frame's [DropTarget], not on
 * Compose), and mirrors the empty-state copy so the affordance reads as the same gesture.
 */
@Composable
fun WindowDropOverlay(modifier: Modifier = Modifier) {
    val strings = blazeStrings
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BlazeColors.accent.copy(alpha = 0.07f))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.94f))
                .border(2.dp, BlazeColors.accent.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                key = AllIconsKeys.Actions.Download,
                contentDescription = null,
                tint = BlazeColors.accent,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = strings.downloads.emptyDropHint,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp),
                color = Color.Unspecified,
            )
        }
    }
}
