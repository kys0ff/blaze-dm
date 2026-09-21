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
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.LoadedResolver
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
    val registry = koinInject<LinkResolverRegistry>()
    val resolverSettings = koinInject<LinkResolverSettingsRepository>()
    val defaultPath = remember { settingsRepository.settings.value.defaultDownloadDir }
    val state = remember { AddDownloadState(defaultPath) }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Keep the inline "which extension will handle this?" notice in sync with what the user
    // has typed, so the outcome is visible *before* Fetch is pressed. Torrents and magnet
    // links never route through a handler, so clear the list for those.
    LaunchedEffect(state.url.text) {
        val source = state.source
        val isHttp = source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true)
        state.detectedHandlers =
            if (isHttp && !state.isBatch) registry.enabledHandlers(source) else emptyList()
    }

    // Defined before fetch() because fetch() references it (Kotlin local functions must be
    // declared before their first use). Resolves a page link to a direct URL, then fetches.
    fun resolveAndFetch(source: String, handler: LoadedResolver) {
        state.showHandlerChoice = false
        state.isFetching = true
        scope.launch {
            registry.resolve(handler, source)
                .onSuccess { resolved ->
                    state.resolvedDirectUrl = resolved.directUrl
                    val fetched = onFetchMetadata(resolved.directUrl)
                    state.metadata = fetched ?: DownloadMetadata(
                        name = resolved.fileName
                            ?: source.substringAfterLast('/').substringBefore('?')
                                .ifBlank { "download" },
                        totalSize = resolved.sizeBytes,
                        files = null
                    )
                    state.step = 2
                }
                .onFailure { e ->
                    state.fetchError = e.message ?: "Couldn't resolve the link"
                }
            state.isFetching = false
        }
    }

    fun fetch() {
        if (!state.canFetch || state.isFetching) return
        state.fetchError = null

        // A batch downloads directly: each entry is fetched in parallel, no link handling.
        if (state.isBatch) {
            state.isFetching = true
            scope.launch {
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
                state.isFetching = false
            }
            return
        }

        val source = state.source
        val isHttp = source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true)
        val handlers = if (isHttp) registry.enabledHandlers(source) else emptyList()

        when {
            // No handler claims this link: resolve it the normal way (direct HTTP / torrent).
            handlers.isEmpty() -> {
                state.isFetching = true
                scope.launch {
                    state.metadata = onFetchMetadata(source)
                    state.step = 2
                    state.isFetching = false
                }
            }
            // A single match is never worth interrupting the user for: use it directly.
            // The AddDownloadInputView already shows an inline notice naming the extension.
            handlers.size == 1 -> resolveAndFetch(source, handlers[0])
            // Several matches: let the user pick, unless they've opted out of being asked.
            resolverSettings.settings.value.alwaysAskHandler -> {
                state.handlerChoices = handlers
                state.showHandlerChoice = true
            }
            else -> resolveAndFetch(source, handlers.first())
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
                state.effectiveSource,
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
                registry = registry,
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

    if (state.showHandlerChoice) {
        HandlerChoiceDialog(
            handlers = state.handlerChoices,
            onDismiss = { state.showHandlerChoice = false },
            onConfirm = { handler -> resolveAndFetch(state.source, handler) }
        )
    }
}
