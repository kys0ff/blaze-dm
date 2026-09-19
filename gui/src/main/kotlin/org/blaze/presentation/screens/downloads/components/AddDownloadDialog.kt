package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.presentation.components.FilePickerDialog
import org.blaze.presentation.components.FilePickerMode
import org.blaze.presentation.theme.IdeColors
import org.blaze.presentation.util.formatSize
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import java.nio.file.Path

@OptIn(ExperimentalJewelApi::class)
@Composable
fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, destination: String, name: String?) -> Unit,
    onFetchMetadata: suspend (url: String) -> DownloadMetadata?
) {
    var url by remember { mutableStateOf(TextFieldValue("")) }
    val defaultPath = remember { Path.of(System.getProperty("user.home"), "Downloads").toString() }
    var destination by remember { mutableStateOf(TextFieldValue(defaultPath)) }
    var showFolderPicker by remember { mutableStateOf(false) }

    var metadata by remember { mutableStateOf<DownloadMetadata?>(null) }
    var isFetching by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(1) } // 1: Input, 2: Metadata

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val source = url.text.trim()
    val canFetch = source.isNotEmpty() && source.isSupportedSource()
    val looksSupported = source.isEmpty() || source.isSupportedSource()

    fun fetch() {
        if (!canFetch || isFetching) return
        isFetching = true
        scope.launch {
            metadata = onFetchMetadata(source)
            isFetching = false
            step = 2
        }
    }

    fun submit() {
        onAdd(source, destination.text, metadata?.name)
        onDismiss()
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val shape = RoundedCornerShape(8.dp)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Add download",
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )

            if (step == 1) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "Download source:")

                        TextField(
                            value = url,
                            onValueChange = {
                                url = it
                                metadata = null
                            },
                            placeholder = { Text("https://…  or  magnet:?xt=…") },
                            outline = if (looksSupported) Outline.None else Outline.Warning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown &&
                                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                    ) {
                                        fetch()
                                        true
                                    } else {
                                        false
                                    }
                                }
                        )

                        if (!looksSupported) {
                            Text(
                                text = "Not an HTTP(S) link, magnet link or .torrent file. It may fail to download.",
                                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                                color = IdeColors.warning
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "Save to:")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = destination,
                                onValueChange = { destination = it },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { showFolderPicker = true }) {
                                Text("Browse...")
                            }
                        }
                    }
                }
            } else {
                // Step 2: Metadata
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Metadata resolved:",
                        style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Medium)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.5f))
                            .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Name:", color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                            Text(metadata?.name ?: "Unknown", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Size:", color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                            Text(formatSize(metadata?.totalSize))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("URL:", color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                            Text(source, maxLines = 1, overflow = TextOverflow.Ellipsis, color = JewelTheme.globalColors.text.info)
                        }
                    }

                    OutlinedButton(onClick = { step = 1 }) {
                        Text("Back to edit")
                    }
                }
            }

            if (isFetching) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IndeterminateHorizontalProgressBar(modifier = Modifier.fillMaxWidth())
                    Text("Fetching metadata...", style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                if (step == 1) {
                    DefaultButton(onClick = ::fetch, enabled = canFetch && !isFetching) {
                        Text("Download")
                    }
                } else {
                    DefaultButton(onClick = ::submit) {
                        Text("Add")
                    }
                }
            }
        }
    }

    if (showFolderPicker) {
        FilePickerDialog(
            onDismiss = { showFolderPicker = false },
            onPick = { path ->
                destination = TextFieldValue(path.toString())
                showFolderPicker = false
            },
            mode = FilePickerMode.Directory,
            initialPath = Path.of(destination.text)
        )
    }
}

private fun String.isSupportedSource(): Boolean {
    val s = trim().lowercase()
    return s.startsWith("http://") ||
            s.startsWith("https://") ||
            s.startsWith("magnet:") ||
            s.endsWith(".torrent")
}
