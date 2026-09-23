package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.presentation.components.ExtensionIcon
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.screens.filepicker.FilePickerDialog
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.util.formatSize
import org.blaze.resolver.core.LinkResolverRegistry
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.koin.compose.koinInject
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalJewelApi::class)
@Composable
fun AddDownloadInputView(
    state: AddDownloadState,
    focusRequester: FocusRequester,
    askWhereToSave: Boolean,
    registry: LinkResolverRegistry,
    onFetch: () -> Unit
) {
    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs

    val isBatch = state.batchItems.isNotEmpty()
    val canFetch = isBatch || state.url.text.isNotBlank()
    var fileError by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(state.scheduleDelay.text.isNotBlank()) }

    // Enter submits, but only when there's something to fetch.
    val submitOnEnter = Modifier.onEnter(enabled = canFetch, action = onFetch)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // ── Source ────────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = dStrings.downloadSource)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val showWarning = !isBatch && state.url.text.isNotBlank() && !state.looksSupported
                val clipboard = koinInject<SystemClipboard>()

                TextField(
                    value = state.url,
                    onValueChange = {
                        state.url = it
                        fileError = null
                        state.resetMetadata()
                    },
                    // A batch is loaded from a file, so the field just names it.
                    readOnly = isBatch,
                    placeholder = { Text(dStrings.addUrlPlaceholder) },
                    outline = when {
                        fileError != null -> Outline.Error
                        showWarning -> Outline.Warning
                        else -> Outline.None
                    },
                    trailingIcon = {
                        if (state.url.text.isEmpty()) {
                            ToolbarIconButton(
                                key = AllIconsKeys.Actions.MenuPaste, tooltip = dStrings.pasteFromClipboard,
                                onClick = {
                                    clipboard.paste()?.let { state.applyPastedText(it, dStrings) }
                                    fileError = null
                                }
                            )
                        } else {
                            ToolbarIconButton(
                                key = AllIconsKeys.General.Close,
                                tooltip = dStrings.clearSource,
                                onClick = {
                                    state.clearSource()
                                    fileError = null
                                }
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .then(submitOnEnter)
                )
                OutlinedButton(onClick = { state.showFilePicker = true }) {
                    Text(dStrings.addFromFile)
                }
            }

            // One hint line, in priority order: error → warning → batch → detected type.
            val kind = if (isBatch) null else sourceKind(state.url.text, dStrings)
            when {
                fileError != null -> FieldHint(fileError!!, HintLevel.Error)
                state.fetchError != null -> FieldHint(state.fetchError!!, HintLevel.Error)
                isBatch -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FieldHint(dStrings.linkCount(state.batchItems.size), HintLevel.Info)
                    Link(text = dStrings.clearSource, onClick = { state.clearSource() })
                }

                !state.looksSupported && state.url.text.isNotBlank() ->
                    FieldHint(dStrings.sourceWarning, HintLevel.Warning)

                else -> {
                    if (kind != null) FieldHint(kind, HintLevel.Info)
                    // Reactive notice: which handler (if any) will resolve this link.
                    HandlerNotice(state, registry, dStrings)
                }
            }
        }

        // ── Destination ───────────────────────────────────────────────────────
        if (askWhereToSave) {
            // Validated by the dialog (it also feeds the size check on the next step), debounced so
            // the disk is not touched on every keystroke.
            val status = state.destinationStatus

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = dStrings.saveTo)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = state.destination,
                        onValueChange = { state.destination = it },
                        outline = if (status.isError) Outline.Error else Outline.None,
                        modifier = Modifier
                            .weight(1f)
                            .then(submitOnEnter)
                    )
                    OutlinedButton(onClick = { state.showFolderPicker = true }) {
                        Text(strings.common.browse)
                    }
                }

                when (status) {
                    is DestinationStatus.Ready ->
                        if (status.freeBytes >= 0) {
                            FieldHint(
                                dStrings.freeSpace(formatSize(status.freeBytes, strings)),
                                HintLevel.Info
                            )
                        }

                    DestinationStatus.WillCreate -> FieldHint(
                        dStrings.destinationWillCreate,
                        HintLevel.Info
                    )

                    DestinationStatus.NotADirectory -> FieldHint(
                        dStrings.destinationNotDirectory,
                        HintLevel.Error
                    )

                    DestinationStatus.NotWritable -> FieldHint(
                        dStrings.destinationNotWritableHint,
                        HintLevel.Error
                    )

                    DestinationStatus.Invalid -> FieldHint(
                        dStrings.destinationInvalidPath,
                        HintLevel.Error
                    )

                    DestinationStatus.Empty -> {}
                }
            }
        }

        // ── Advanced (collapsed unless a delay is already set) ───────────────
        CollapsibleSection(
            title = dStrings.advancedOptions,
            expanded = showAdvanced,
            onToggle = { showAdvanced = !showAdvanced }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = dStrings.scheduleDelayLabel)
                TextField(
                    value = state.scheduleDelay,
                    onValueChange = { state.scheduleDelay = it },
                    placeholder = { Text(dStrings.scheduleDelayPlaceholder) },
                    modifier = Modifier.fillMaxWidth().then(submitOnEnter)
                )
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    if (state.showFolderPicker) {
        FilePickerDialog(
            onDismiss = { state.showFolderPicker = false },
            onPick = { path ->
                state.destination =
                    TextFieldValue(path.toString(), TextRange(path.toString().length))
                state.showFolderPicker = false
            },
            mode = FilePickerMode.Directory,
            // Path.of() throws on malformed input (easy on Windows) → never let it crash the dialog.
            initialPath = runCatching { Path.of(state.destination.text.trim()) }
                .getOrNull()
                ?.takeIf { Files.isDirectory(it) }
        )
    }

    if (state.showFilePicker) {
        FilePickerDialog(
            onDismiss = { state.showFilePicker = false },
            onPick = { path ->
                fileError = state.loadSourceFile(path, dStrings)
                state.showFilePicker = false
            },
            mode = FilePickerMode.File,
            title = dStrings.selectSourceFile,
            supportedExtensions = listOf("torrent", "txt"),
            fileFilter = { path ->
                val ext = path.toFile().extension.lowercase()
                ext == "torrent" || ext == "txt"
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// State helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun AddDownloadState.clearSource() {
    batchItems.clear()
    url = TextFieldValue("")
    resetMetadata()
}

private fun AddDownloadState.setSource(urls: List<String>, batchLabel: String) {
    resetMetadata()
    batchItems.clear() // picking a second file must replace the batch, not append to it
    when {
        urls.size > 1 -> {
            url = TextFieldValue(batchLabel)
            batchItems.addAll(urls.map { BatchDownloadItem(it) })
        }

        urls.size == 1 -> url = TextFieldValue(urls[0], TextRange(urls[0].length))
    }
}

/** Pasting several lines becomes a batch; a single line just fills the field. */
private fun AddDownloadState.applyPastedText(
    raw: String,
    d: BlazeStrings.Downloads.Dialogs,
) {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    setSource(lines, d.linkCount(lines.size))
}

/** Returns an error message, or null on success. */
private fun AddDownloadState.loadSourceFile(
    path: Path,
    d: BlazeStrings.Downloads.Dialogs,
): String? {
    val file = path.toFile()
    if (!file.extension.equals("txt", ignoreCase = true)) {
        // .TORRENT and friends: use the path as the source
        setSource(listOf(path.toString()), "")
        return null
    }
    val urls = runCatching { file.readLines() }
        .getOrElse { return d.sourceFileReadFailed(it.message) }
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") } // allow comments in link lists
        .distinct()

    if (urls.isEmpty()) return d.emptyLinkFile
    setSource(urls, file.name)
    return null
}

private fun sourceKind(text: String, d: BlazeStrings.Downloads.Dialogs): String? {
    val t = text.trim()
    return when {
        t.startsWith("MAGNET:", ignoreCase = true) -> d.sourceKindMagnet
        t.endsWith(".TORRENT", ignoreCase = true) -> d.sourceKindTorrent
        t.startsWith("http://", ignoreCase = true) || t.startsWith(
            "https://",
            ignoreCase = true
        ) -> d.sourceKindHttp

        else -> null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Destination validation lives in DestinationStatus.kt, shared with the dialog and
// the review step so all three agree on what a folder promises.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// Small UI building blocks
// ─────────────────────────────────────────────────────────────────────────────

private fun Modifier.onEnter(enabled: Boolean, action: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (enabled &&
            event.type == KeyEventType.KeyDown &&
            (event.key == Key.Enter || event.key == Key.NumPadEnter)
        ) {
            action()
            true
        } else {
            false
        }
    }

private enum class HintLevel { Info, Warning, Error }

@Composable
private fun FieldHint(text: String, level: HintLevel) {
    Text(
        text = text,
        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
        color = when (level) {
            HintLevel.Info -> JewelTheme.globalColors.text.info
            HintLevel.Warning -> BlazeColors.warning
            HintLevel.Error -> BlazeColors.error
        }
    )
}

/**
 * Reactive notice explaining what will happen to a typed link:
 *  - exactly one enabled handler claims it: name that extension (with its icon),
 *  - several handlers claim it: hint that the picker will show,
 *  - otherwise: nothing (the engine will fetch it directly).
 */
@Composable
private fun HandlerNotice(
    state: AddDownloadState,
    registry: LinkResolverRegistry,
    dStrings: BlazeStrings.Downloads.Dialogs
) {
    when {
        state.detectedHandlers.size == 1 -> {
            val handler = state.detectedHandlers.first().resolver
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExtensionIcon(state.detectedHandlers.first(), registry, size = 16.dp)
                Text(
                    text = dStrings.handlerWillBeUsed(handler.displayName),
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                    color = JewelTheme.globalColors.text.info
                )
            }
        }

        state.detectedHandlers.size > 1 ->
            FieldHint(
                dStrings.handlerWillAsk(state.detectedHandlers.size),
                HintLevel.Info
            )
    }
}

/** IDE-style "▸ Advanced options" disclosure. */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                key = if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(text = title)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, top = 6.dp)) { content() }
        }
    }
}