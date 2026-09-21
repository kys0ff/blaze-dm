package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.blaze.domain.repository.DownloadFile
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.IdeDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.blaze.presentation.screens.downloads.components.add.AddDownloadInputView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadListView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadMetadataView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.Text
import org.koin.compose.koinInject

@OptIn(ExperimentalJewelApi::class)
@Composable
fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onAdd: (
        url: String,
        destination: String,
        name: String?,
        fileIndices: List<Int>?,
        totalSize: Long?,
        files: List<DownloadFile>?,
        scheduledAt: Long?
    ) -> Unit,
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
                if (state.metadata?.files != null) state.selectedFileIndices.toList()
                    .sorted() else null,
                state.metadata?.totalSize,
                state.metadata?.files,
                scheduledAt
            )
        }
        onDismiss()
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs

    // The primary action label/handler depend on the current step, so resolve them once.
    val (confirmText, onConfirm, confirmEnabled) = if (state.step == 1) {
        Triple(dStrings.addDownload, ::fetch, state.canFetch && !state.isFetching)
    } else {
        val enabled = if (state.isBatch) {
            state.batchItems.any { it.isSelected }
        } else {
            state.metadata?.files == null || state.selectedFileIndices.isNotEmpty()
        }
        Triple(dStrings.addAction, ::submit, enabled)
    }

    // Focus stays on the source field (managed above), so the shell must not grab it.
    IdeDialog(onDismiss = onDismiss, requestFocus = false) {
        IdeDialogTitle(if (state.isBatch) dStrings.batchTitle else dStrings.addTitle)

        when {
            state.step == 1 -> AddDownloadInputView(
                state = state,
                focusRequester = focusRequester,
                askWhereToSave = settingsRepository.settings.value.askWhereToSave,
                onFetch = ::fetch
            )

            state.isBatch -> AddDownloadListView(state = state)

            else -> AddDownloadMetadataView(state = state)
        }

        if (state.isFetching) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IndeterminateHorizontalProgressBar(modifier = Modifier.fillMaxWidth())
                Text(
                    text = dStrings.fetchingMetadata,
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
                )
            }
        }

        IdeDialogActions(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = confirmText,
            onConfirm = onConfirm,
            confirmEnabled = confirmEnabled
        )
    }
}
