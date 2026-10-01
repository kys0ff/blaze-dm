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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blaze.domain.repository.DownloadFile
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.domain.repository.TorrentPreview
import org.blaze.data.AppSettingsRepository
import org.blaze.download.api.DownloadPriority
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BaseDialog
import org.blaze.presentation.components.StandardDialogButtons
import org.blaze.presentation.screens.downloads.components.add.AddDownloadInputView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadListView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadMetadataView
import org.blaze.presentation.screens.downloads.components.add.AddDownloadState
import org.blaze.presentation.screens.downloads.components.add.AddDownloadFetchOutcome
import org.blaze.presentation.screens.downloads.components.add.ResolvedPick
import org.blaze.presentation.screens.downloads.components.add.isHttpSource
import org.blaze.presentation.screens.downloads.components.add.isHttpTorrentFileUrl
import org.blaze.presentation.screens.downloads.components.add.loadSourceFile
import org.blaze.presentation.screens.downloads.components.add.resolvedLinkToMetadata
import org.blaze.presentation.screens.downloads.components.add.resolvedLinksToBatchItems
import org.blaze.presentation.screens.downloads.components.add.resolveDestinationStatus
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.LoadedResolver
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography
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
        priority: DownloadPriority,
        credentials: Map<String, String>,
    ) -> Unit,
    onFetchMetadata: suspend (url: String, credentials: Map<String, String>) -> DownloadMetadata?,
    onPrepareTorrent: suspend (url: String, credentials: Map<String, String>) -> TorrentPreview?,
    initialSource: String? = null
) {
    val settingsRepository = koinInject<EngineSettingsRepository>()
    val appSettingsRepository = koinInject<AppSettingsRepository>()
    // Opt-in: when off, a `.torrent` URL keeps the existing behavior (probe the URL through the swarm).
    val torrentFilePreviewEnabled = appSettingsRepository.settings.value.torrentFilePreviewEnabled
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

    // Declared before resolveAndFetch because that function calls it from inside a coroutine
    // (Kotlin local functions must be declared before their first use). It probes the network, so
    // it is suspend: every caller runs it inside the dialog scope.
    /** Reviews a single resolved link: probe it, falling back to what the handler announced. */
    suspend fun reviewResolvedLink(resolved: ResolvedLink) {
        state.resolvedDirectUrl = resolved.directUrl
        state.resolvedHeaders = resolved.headers
        val fetched = onFetchMetadata(resolved.directUrl, state.effectiveCredentials)
        state.metadata = fetched ?: resolvedLinkToMetadata(resolved)
        state.step = 2
    }

    // Resolves a page link to one or more direct URLs, then either reviews a single link or
    // offers a multi-link pick.
    fun resolveAndFetch(source: String, handler: LoadedResolver) {
        state.showHandlerChoice = false
        state.isFetching = true
        scope.launch {
            registry.resolveAll(handler, source)
                .onSuccess { links ->
                    when {
                        links.isEmpty() -> state.fetchError = dStrings.noLinksResolved
                        links.size == 1 -> reviewResolvedLink(links.first())
                        else -> {
                            state.resolvedLinks = links
                            state.showLinkChoice = true
                        }
                    }
                }
                .onFailure { e ->
                    state.fetchError = e.message ?: "Couldn't resolve the link"
                }
            state.isFetching = false
        }
    }

    /** Turns the user's multi-link pick into either a single review or a batch review list. */
    fun applyResolvedPick(pick: ResolvedPick) {
        state.showLinkChoice = false
        when (pick) {
            ResolvedPick.None -> Unit
            is ResolvedPick.Single -> scope.launch { reviewResolvedLink(pick.link) }
            is ResolvedPick.Many -> {
                // Several links from one page share its headers (the same Referer).
                state.resolvedHeaders = pick.links.firstOrNull()?.headers ?: emptyMap()
                state.batchItems.clear()
                state.batchItems.addAll(resolvedLinksToBatchItems(pick.links))
                state.url = TextFieldValue(dStrings.linkCount(pick.links.size))
                state.step = 2
            }
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
            val metadata = runCatching { onFetchMetadata(source, state.effectiveCredentials) }
            state.isFetching = false
            applyOutcome(source, metadata.getOrNull(), metadata.exceptionOrNull()?.message)
        }
    }

    // The opt-in torrent-preview path for a `.torrent` download link. Rather than leave the engine to
    // resolve the URL through the swarm (which can stall and only ever previews a URL source), fetch the
    // metainfo file directly, preview the files inside it, and remember its local path so submitting
    // queues a normal local torrent. Any failure falls back to the ordinary probe so the switch on can
    // never be worse than the switch off.
    fun prepareTorrentAndReview(source: String) {
        state.isFetching = true
        scope.launch {
            val preview = runCatching { onPrepareTorrent(source, state.effectiveCredentials) }.getOrNull()
            state.isFetching = false
            if (preview == null) {
                probeDirectly(source)
                return@launch
            }
            state.preparedTorrentFile = preview.torrentFile
            state.metadata = DownloadMetadata(
                name = preview.name,
                totalSize = preview.totalSize,
                files = preview.files,
                resumeSupported = true,
            )
            state.metadataUnresolved = false
            state.step = 2
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
                            item.metadata = onFetchMetadata(item.url, state.effectiveCredentials)
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
            handlers.isEmpty() -> when {
                state.looksLikeWebPage -> state.pendingPlainPageSource = source
                // A `.torrent` link with the preview switch on: download, review and add as a local torrent.
                torrentFilePreviewEnabled && isHttpTorrentFileUrl(source) -> prepareTorrentAndReview(source)
                else -> probeDirectly(source)
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
                    state.priority,
                    state.effectiveCredentials
                )
            }
        } else {
            onAdd(
                // When a `.torrent` link was previewed, submit the cached local metainfo file so the
                // transfer runs as a normal local torrent; otherwise keep the source as typed.
                state.preparedTorrentFile ?: state.effectiveSource,
                state.destination.text,
                state.metadata?.name,
                if (state.metadata?.files != null) state.selectedFileIndices.toList()
                    .sorted() else null,
                state.metadata?.totalSize,
                state.metadata?.files,
                scheduledAt,
                state.priority,
                state.effectiveCredentials
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
    val dialogTitle = if (state.isBatch) dStrings.batchTitle else dStrings.addTitle
    BaseDialog(onDismiss = onDismiss, requestFocus = false, title = dialogTitle) {
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
                    style = JewelTheme.typography.small
                )
            }
        }

        StandardDialogButtons(
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

    if (state.showLinkChoice) {
        ResolvedLinksChoiceDialog(
            links = state.resolvedLinks,
            onDismiss = { state.showLinkChoice = false },
            onConfirm = ::applyResolvedPick
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
