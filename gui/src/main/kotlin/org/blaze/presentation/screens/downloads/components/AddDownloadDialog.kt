package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blaze.data.AppSettingsRepository
import org.blaze.domain.repository.DownloadFile
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.domain.repository.TorrentPreview
import org.blaze.download.api.*
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.downloads.components.add.*
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.LoadedResolver
import org.island.ui.dialog.IslandDialog
import org.island.ui.dialog.StandardDialogButtons
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
    onInspectHls: suspend (url: String, credentials: Map<String, String>) -> HlsInspectionDto? = { _, _ -> null },
    onInspectArchive: suspend (url: String, credentials: Map<String, String>) -> ArchiveInspectionDto? = { _, _ -> null },
    /** Queues a source the dialog validated by inspection (HLS variant / archive entry selection). */
    onAddTyped: (
        source: DownloadSource,
        destination: String,
        name: String?,
        totalSize: Long?,
        scheduledAt: Long?,
        priority: DownloadPriority,
        credentials: Map<String, String>,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
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

    // A slow or half-dead server can leave the spinner turning for a long time with no feedback, which
    // reads as a freeze. Rather than go silent, the label starts as the plain verb and escalates into a
    // friendly nudge as the wait grows, so the user knows Blaze is still on it.
    var fetchHint by remember { mutableStateOf(dStrings.fetchingMetadata) }
    LaunchedEffect(state.isFetching) {
        if (!state.isFetching) return@LaunchedEffect
        fetchHint = dStrings.fetchingMetadata
        delay(FETCH_HINT_SLOW_MS.milliseconds)
        fetchHint = dStrings.fetchingSlow
        delay(FETCH_HINT_TROUBLE_MS.milliseconds)
        fetchHint = dStrings.fetchingTrouble
    }

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

    // Inspects an `.m3u8` link the user typed as an HLS stream. The engine's verdict — not the
    // filename — decides what happens: a playable VOD goes to the quality review, a stream that looks
    // DRM/uncertain-protected raises the warning first, and a live/non-stream/error is reported plainly.
    // Nothing here downloads the playlist as a file; a failed probe keeps the user on the input step.
    fun inspectHlsAndReview(source: String) {
        state.isFetching = true
        scope.launch {
            val inspection = runCatching { onInspectHls(source, state.effectiveCredentials) }.getOrNull()
            state.isFetching = false
            if (inspection == null) {
                state.fetchError = dStrings.hls.notAStream // transport could not reach the playlist
                return@launch
            }
            state.hlsInspection = inspection
            state.selectedHlsVariant = inspection.selectedVariantIndex
            when (inspection.status) {
                HlsInspectStatusDto.Playable -> {
                    state.reviewedKind = AddDownloadState.ReviewedKind.HLS
                    state.step = 2
                }
                // Credible DRM/uncertain protection: warn before anything starts. The warning stays up
                // over the input step; only "Continue" advances, "Cancel" returns here without a download.
                HlsInspectStatusDto.NeedsDrmConsent -> state.showDrmWarning = true
                else -> {
                    state.hlsInspection = null
                    state.fetchError = inspection.message ?: dStrings.hls.notAStream
                }
            }
        }
    }

    // Inspects a remote `.zip` link via byte ranges. A browsable, range-served archive opens the entry
    // browser; one that parses but will not serve ranges (or is only readable in full) requires an
    // explicit acknowledgement before the whole archive is fetched; a non-archive is reported plainly.
    fun inspectArchiveAndReview(source: String) {
        state.isFetching = true
        scope.launch {
            val inspection = runCatching { onInspectArchive(source, state.effectiveCredentials) }.getOrNull()
            state.isFetching = false
            if (inspection == null) {
                state.fetchError = dStrings.archive.unavailable("")
                return@launch
            }
            when (inspection) {
                is ArchiveInspectionDto.Browsable -> {
                    state.archiveInspection = inspection
                    state.reviewedKind = AddDownloadState.ReviewedKind.ARCHIVE
                    state.pendingArchiveFullDownloadReason =
                        if (inspection.selectiveSupported) null else inspection.reasonIfUnsupported
                    // Default to "everything selected" so the tree reads fully-checked, mirroring the
                    // multi-file torrent review; deselecting narrows the retrieval.
                    if (inspection.selectiveSupported) {
                        state.selectedArchiveEntries =
                            inspection.entries.filter { !it.isDirectory && it.extractable }.map { it.name }.toSet()
                    }
                    state.step = 2
                }

                is ArchiveInspectionDto.FullDownloadOnly -> {
                    state.archiveInspection = inspection
                    state.reviewedKind = AddDownloadState.ReviewedKind.ARCHIVE
                    state.pendingArchiveFullDownloadReason = inspection.reason
                    state.step = 2
                }

                is ArchiveInspectionDto.Unavailable -> {
                    state.fetchError = dStrings.archive.unavailable(inspection.reason)
                }
            }
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
                // A playlist-shaped link is inspected as a stream before anything is queued; the engine's
                // verdict (not the extension) decides playable / DRM-warning / unsupported.
                isHlsPlaylistUrl(source) -> inspectHlsAndReview(source)
                // A zip-shaped link is inspected through byte ranges so its entries can be browsed and
                // picked without pulling the whole archive.
                isRemoteArchiveUrl(source) -> inspectArchiveAndReview(source)
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

        // An inspected HLS stream or remote archive is queued as a typed source carrying the choices the
        // user made in the review step (variant + DRM acknowledgement, or entry selection). This bypasses
        // the bare-URL inference so the handler is the one the inspection validated, never a guess.
        typedDownloadSource(state)?.let { source ->
            val announcedBytes = when (state.reviewedKind) {
                AddDownloadState.ReviewedKind.HLS -> state.hlsInspection?.estimatedTotalBytes
                AddDownloadState.ReviewedKind.ARCHIVE -> state.archiveSelectedBytes
                AddDownloadState.ReviewedKind.PLAIN -> null
            }
            onAddTyped(
                source,
                state.destination.text,
                state.metadata?.name,
                announcedBytes,
                scheduledAt,
                state.priority,
                state.effectiveCredentials,
            )
            onDismiss()
            return
        }

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
        } else when (state.reviewedKind) {
            // An archive must be either narrowed to a non-empty selection or explicitly acknowledged as
            // a whole-archive fetch when ranges are unavailable - never a silent large download.
            AddDownloadState.ReviewedKind.ARCHIVE ->
                if (state.archiveCanSelect) state.selectedArchiveEntries.isNotEmpty()
                else state.archiveDownloadAllAcknowledged

            AddDownloadState.ReviewedKind.HLS ->
                // A stream needs no file selection; it only needs a destination that can hold the estimate.
                true

            AddDownloadState.ReviewedKind.PLAIN ->
                state.metadata?.files == null || state.selectedFileIndices.isNotEmpty()
        } && !state.destinationBlocked
        Triple(dStrings.addAction, ::submit, enabled)
    }

    // Focus stays on the source field (managed above), so the shell must not grab it.
    val dialogTitle = if (state.isBatch) dStrings.batchTitle else dStrings.addTitle
    IslandDialog(onDismiss = onDismiss, requestFocus = false, title = dialogTitle) {
        when {
            state.step == 1 -> AddDownloadInputView(
                state = state,
                focusRequester = focusRequester,
                askWhereToSave = settingsRepository.settings.value.askWhereToSave,
                registry = registry,
                onFetch = ::fetch
            )

            state.isBatch -> AddDownloadListView(state = state)

            state.reviewedKind == AddDownloadState.ReviewedKind.HLS ->
                AddDownloadHlsReviewView(state = state)

            state.reviewedKind == AddDownloadState.ReviewedKind.ARCHIVE ->
                AddDownloadArchiveView(state = state)

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
                    text = fetchHint,
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
            // Cancelling the warning means "I don't want the page" — there's nothing left to do here,
            // so the add dialog is dismissed alongside it rather than dropped back on an empty step.
            onDismiss = {
                state.pendingPlainPageSource = null
                onDismiss()
            },
            onProceed = {
                state.pendingPlainPageSource = null
                probeDirectly(source)
            }
        )
    }

    // Potential-DRM warning raised after an HLS inspection classified the stream as protected or of
    // uncertain protection. Continuing records the acknowledgement and moves to the quality review so a
    // normal, supported download can be attempted; cancelling leaves the add dialog on its input step and
    // starts nothing. Acknowledging never unlocks any DRM circumvention.
    if (state.showDrmWarning) {
        DrmWarningDialog(
            protection = state.hlsInspection?.protection,
            onDismiss = { state.showDrmWarning = false },
            onContinue = {
                state.showDrmWarning = false
                state.drmAcknowledged = true
                state.reviewedKind = AddDownloadState.ReviewedKind.HLS
                state.step = 2
            },
        )
    }
}

/** Keeps a folder that is being typed at from being stat-ed on every keystroke. */
private const val DESTINATION_CHECK_DEBOUNCE_MS = 200L

/** How long a fetch runs before the hint softens into "still working on it". */
private const val FETCH_HINT_SLOW_MS = 4_000L

/** Additional wait before the hint admits it may be "having a little trouble". */
private const val FETCH_HINT_TROUBLE_MS = 6_000L
