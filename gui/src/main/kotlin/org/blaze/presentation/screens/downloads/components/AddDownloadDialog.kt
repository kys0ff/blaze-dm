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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blaze.domain.repository.DownloadFile
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.download.api.DownloadPriority
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.IdeDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.blaze.presentation.screens.downloads.components.add.AddDownloadInputView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadListView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadMetadataView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadState
import org.blaze.presentation.screens.downloads.components.add.AddDownloadFetchOutcome
import org.blaze.presentation.screens.downloads.components.add.isHttpSource
import org.blaze.presentation.screens.downloads.components.add.loadSourceFile
import org.blaze.presentation.screens.downloads.components.add.resolveDestinationStatus
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.LoadedResolver
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.Text
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

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
        scheduledAt: Long?,
        priority: DownloadPriority
    ) -> Unit,
    onFetchMetadata: suspend (url: String) -> DownloadMetadata?,
    initialSource: String? = null
) {
    val settingsRepository = koinInject<EngineSettingsRepository>()
    val registry = koinInject<LinkResolverRegistry>()
    val resolverSettings = koinInject<LinkResolverSettingsRepository>()
    val defaultPath = remember { settingsRepository.settings.value.defaultDownloadDir }
    val defaultPriority = remember {
        // The engine stores its own priority enum; map by name onto the api enum the GUI uses.
        DownloadPriority.valueOf(settingsRepository.settings.value.defaultDownloadPriority.name)
    }
    // An external open (OS file association / magnet link / window drop) arrives with the source
    // already filled in - exactly what the user would have pasted - so every normal step still
    // applies. A source that names an existing local file goes through the same loader the "Add
    // from File" button uses, so a dropped link list lands in the batch review instead of as a
    // literal path in the URL field.
    val dStrings = blazeStrings.downloads.dialogs
    val state = remember {
        AddDownloadState(defaultPath).apply {
            priority = defaultPriority
            if (initialSource != null) {
                val artifact = runCatching { java.nio.file.Path.of(initialSource) }.getOrNull()
                    ?.takeIf { java.nio.file.Files.isRegularFile(it) }
                if (artifact != null) {
                    loadSourceFile(artifact, dStrings)?.let {
                        // Unreadable/empty list: still show the raw path so the user can react.
                        fetchError = it
                        url = TextFieldValue(initialSource)
                    }
                } else {
                    url = TextFieldValue(initialSource)
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // The save folder is checked here rather than in the input view, because the review step needs
    // the answer too - does the announced size fit? - and the folder is known whether the
    // user was asked to pick one.
    LaunchedEffect(state.destination.text) {
        delay(DESTINATION_CHECK_DEBOUNCE_MS.milliseconds)
        state.destinationStatus =
            withContext(Dispatchers.IO) { resolveDestinationStatus(state.destination.text) }
    }

    // Keep the inline "which extension will handle this?" notice in sync with what the user
    // has typed, so the outcome is visible *before* Fetch is pressed. Torrents and magnet
    // links never route through a handler, so clear the list for those.
    LaunchedEffect(state.url.text) {
        val source = state.source
        state.detectedHandlers =
            if (isHttpSource(source) && !state.isBatch) registry.enabledHandlers(source) else emptyList()
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

    // A probe answers in one of three ways, and each has its own contract with the user:
    // resolved -> normal review; unresolved torrent -> review degraded but queueable (a cold
    // magnet is normal, not an error); rejected HTTP -> stay on the input step, because a
    // link that refused to answer only queues a download that is born failing.
    fun applyOutcome(source: String, metadata: DownloadMetadata?, probeError: String?) {
        when (val outcome = AddDownloadFetchOutcome.of(source, metadata, probeError)) {
            is AddDownloadFetchOutcome.Resolved -> {
                state.metadata = outcome.metadata
                state.metadataUnresolved = false
                state.step = 2
            }
            is AddDownloadFetchOutcome.Unresolved -> {
                state.metadata = null
                state.metadataUnresolved = true
                state.step = 2
            }
            is AddDownloadFetchOutcome.Rejected ->
                // [reason] carries the offending source (or the probe's exception message).
                state.fetchError = dStrings.httpProbeFailed(outcome.reason)
        }
    }

    /** Fetch [source] exactly as typed: the no-handler path, which saves the server's response. */
    fun probeDirectly(source: String) {
        state.isFetching = true
        scope.launch {
            val metadata = runCatching { onFetchMetadata(source) }
            state.isFetching = false
            applyOutcome(source, metadata.getOrNull(), metadata.exceptionOrNull()?.message)
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
                            // A freshly resolved multi-file torrent starts fully selected; the
                            // submit step sends this set as the download's file indices.
                            item.selectedFileIndices =
                                item.metadata?.files?.map { it.index }?.toSet() ?: emptySet()
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
        val handlers = if (isHttpSource(source)) registry.enabledHandlers(source) else emptyList()

        when {
            // No handler claims this link, so it is fetched as it stands. When the link reads like
            // a web page rather than a file, that fetch saves the page — HTML the user almost
            // certainly didn't want — so the outcome is confirmed before a request is spent, and
            // only a "yes" turns it into the plain-page download they asked for.
            handlers.isEmpty() ->
                if (state.looksLikeWebPage) state.pendingPlainPageSource = source
                else probeDirectly(source)
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

    // Fetch once on arrival for external opens so the user lands directly on the normal
    // metadata/destination step without having to press anything (fetch() re-checks canFetch).
    // A batch is the exception: the review list is the landing step, and firing metadata for
    // dozens of links unprompted is not what an unrequested drop should cost.
    if (initialSource != null && !state.isBatch) {
        LaunchedEffect(Unit) { fetch() }
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
                // Carry the per-item selection, not the whole file list: a magnet reviewed in a
                // batch with two of its ten files deselected must queue exactly those two.
                val selectedIndices = item.metadata?.files
                    ?.filter { it.index in item.selectedFileIndices }
                    ?.map { it.index }
                    ?.sorted()
                onAdd(
                    item.url,
                    state.destination.text,
                    item.metadata?.name,
                    selectedIndices?.takeIf { it.isNotEmpty() },
                    item.metadata?.totalSize,
                    item.metadata?.files,
                    scheduledAt,
                    state.priority
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
                scheduledAt,
                state.priority
            )
        }
        onDismiss()
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val strings = blazeStrings
    // dStrings was resolved above, next to the state it also seeds.

    // The primary action label/handler depend on the current step, so resolve them once.
    val (confirmText, onConfirm, confirmEnabled) = if (state.step == 1) {
        Triple(dStrings.addDownload, ::fetch, state.canFetch && !state.isFetching)
    } else {
        val enabled = if (state.isBatch) {
            state.batchItems.any { it.isSelected }
        } else {
            state.metadata?.files == null || state.selectedFileIndices.isNotEmpty()
        } && !state.destinationBlocked
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

    state.pendingPlainPageSource?.let { source ->
        PlainPageDownloadDialog(
            url = source,
            onDismiss = { state.pendingPlainPageSource = null },
            onProceed = {
                state.pendingPlainPageSource = null
                probeDirectly(source)
            }
        )
    }
}

/** Keeps a folder that is being typed at from being stat-ed on every keystroke. */
private const val DESTINATION_CHECK_DEBOUNCE_MS = 200L
