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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.blaze.domain.repository.DownloadFile
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.downloads.components.add.AddDownloadInputView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadListView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadMetadataView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.koin.compose.koinInject

@OptIn(ExperimentalJewelApi::class)
@Composable
fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, destination: String, name: String?, fileIndices: List<Int>?, totalSize: Long?, files: List<DownloadFile>?, scheduledAt: Long?) -> Unit,
    onFetchMetadata: suspend (url: String) -> DownloadMetadata?
) {
    val settingsRepository = koinInject<EngineSettingsRepository>()
    val defaultPath = remember { settingsRepository.settings.value.defaultDownloadDir }
    val state = remember { AddDownloadState(defaultPath) }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    fun fetch() {
        if (!state.canFetch || state.isFetching) return
        state.isFetching = true
        scope.launch {
            if (state.isBatch) {
                state.step = 2
                state.batchItems.forEach { item ->
                    launch {
                        item.isFetching = true
                        try {
                            item.metadata = onFetchMetadata(item.url)
                        } catch (e: Exception) {
                            item.error = e.message
                        } finally {
                            item.isFetching = false
                        }
                    }
                }
            } else {
                state.metadata = onFetchMetadata(state.source)
                state.step = 2
            }
            state.isFetching = false
        }
    }

    LaunchedEffect(state.metadata) {
        state.metadata?.files?.let { files ->
            state.selectedFileIndices = files.map { it.index }.toSet()
        } ?: run {
            state.selectedFileIndices = emptySet()
        }
    }

    fun submit() {
        val delayMinutes = state.scheduleDelay.text.trim().toLongOrNull()
        val scheduledAt = if (delayMinutes != null && delayMinutes > 0) {
            System.currentTimeMillis() + delayMinutes * 60 * 1000
        } else null

        if (state.isBatch) {
            state.batchItems.filter { it.isSelected }.forEach { item ->
                onAdd(
                    item.url,
                    state.destination.text,
                    item.metadata?.name,
                    item.metadata?.files?.map { it.index },
                    item.metadata?.totalSize,
                    item.metadata?.files,
                    scheduledAt
                )
            }
        } else {
            onAdd(
                state.source,
                state.destination.text,
                state.metadata?.name,
                if (state.metadata?.files != null) state.selectedFileIndices.toList().sorted() else null,
                state.metadata?.totalSize,
                state.metadata?.files,
                scheduledAt
            )
        }
        onDismiss()
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val strings = blazeStrings
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
                text = if (state.isBatch) strings.downloads.dialogs.batchTitle else strings.downloads.dialogs.addTitle,
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )

            if (state.step == 1) {
                AddDownloadInputView(
                    state = state,
                    focusRequester = focusRequester,
                    askWhereToSave = settingsRepository.settings.value.askWhereToSave,
                    onFetch = ::fetch
                )
            } else if (state.isBatch) {
                AddDownloadListView(state = state)
            } else {
                AddDownloadMetadataView(state = state)
            }

            if (state.isFetching) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IndeterminateHorizontalProgressBar(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = strings.downloads.dialogs.fetchingMetadata,
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
                    )
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
                    Text(strings.common.cancel)
                }
                Spacer(Modifier.width(8.dp))
                if (state.step == 1) {
                    DefaultButton(onClick = ::fetch, enabled = state.canFetch && !state.isFetching) {
                        Text(strings.downloads.dialogs.addDownload)
                    }
                } else {
                    DefaultButton(
                        onClick = ::submit,
                        enabled = if (state.isBatch) {
                            state.batchItems.any { it.isSelected }
                        } else {
                            state.metadata?.files == null || state.selectedFileIndices.isNotEmpty()
                        }
                    ) {
                        Text(strings.downloads.dialogs.addAction)
                    }
                }
            }
        }
    }
}
