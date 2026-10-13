package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.download.api.DownloadPriority
import org.blaze.download.api.DownloadSource
import org.blaze.i18n.BlazeStrings
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.core.LoadedResolver
import java.io.File
import java.nio.file.Path

class AddDownloadState(
    defaultDestination: String
) {
    var url by mutableStateOf(TextFieldValue(""))
    var destination by mutableStateOf(TextFieldValue(defaultDestination))
    var scheduleDelay by mutableStateOf(TextFieldValue(""))
    var showFolderPicker by mutableStateOf(false)
    var showFilePicker by mutableStateOf(false)

    /**
     * Ephemeral authentication material for an HTTP(S) source that needs a signed-in request: a
     * bearer token and/or a raw Cookie header. These are folded into [credentials] and handed to the
     * transfer only - the API keeps them out of the DTO, the database and the log - so they are
     * deliberately not part of [resetMetadata] (a URL edit must not throw away what was typed).
     */
    var bearerToken by mutableStateOf(TextFieldValue(""))
    var cookie by mutableStateOf(TextFieldValue(""))

    /** The combined auth headers to send for this transfer; empty when nothing was entered. */
    val credentials: Map<String, String> get() = buildMap {
        bearerToken.text.trim().takeIf { it.isNotEmpty() }?.let { put("Authorization", "Bearer $it") }
        cookie.text.trim().takeIf { it.isNotEmpty() }?.let { put("Cookie", it) }
    }

    /** Scheduling importance for the download(s) about to be queued; seeded from the global default. */
    var priority by mutableStateOf(DownloadPriority.NORMAL)

    var metadata by mutableStateOf<DownloadMetadata?>(null)

    /**
     * The local path of a `.torrent` metainfo file downloaded from an http(s) link during the preview step.
     * When non-null, [submit][org.blaze.presentation.screens.downloads.components.AddDownloadDialog] queues
     * the download from this local file (a normal torrent) instead of re-fetching the URL. Cleared whenever
     * the source is edited, so a stale preview can never leak into a different download.
     */
    var preparedTorrentFile by mutableStateOf<String?>(null)

    /**
     * True while step 2 renders a torrent whose metadata the swarm hasn't supplied yet - the
     * review step shows a "details pending" notice instead of a name/size/file list.
     */
    var metadataUnresolved by mutableStateOf(false)
    var selectedFileIndices by mutableStateOf(emptySet<Int>())
    var isFetching by mutableStateOf(false)
    var step by mutableStateOf(1) // 1: Input, 2: Metadata/Batch

    /**
     * Live verdict on [destination], recomputed by the dialog as the folder is edited. The views
     * render it, and the dialog refuses to queue a download on top of it.
     */
    var destinationStatus by mutableStateOf<DestinationStatus>(DestinationStatus.Empty)

    // Link-handler resolution: the direct URL extracted from a page link, and the picker state.
    var resolvedDirectUrl by mutableStateOf<String?>(null)
    var showHandlerChoice by mutableStateOf(false)
    var handlerChoices by mutableStateOf<List<LoadedResolver>>(emptyList())
    var fetchError by mutableStateOf<String?>(null)

    /**
     * Extra request headers the resolved link carries (e.g. a required Referer). Folded into
     * [effectiveCredentials] so the metadata probe and the queued transfer both send them; a
     * page that resolves to nothing leaves this empty and behaves exactly like a direct URL.
     */
    var resolvedHeaders by mutableStateOf<Map<String, String>>(emptyMap())

    /** Every link a handler extracted from the page, pending the user's pick when there are several. */
    var resolvedLinks by mutableStateOf<List<ResolvedLink>>(emptyList())
    var showLinkChoice by mutableStateOf(false)

    /**
     * The source awaiting the user's "download the page anyway" answer, or null when nothing is
     * pending. Held as a captured string rather than read back from [url] at confirm time, so the
     * link described by the warning is exactly the one that gets fetched.
     */
    var pendingPlainPageSource by mutableStateOf<String?>(null)

    /**
     * Enabled handlers that currently claim they can process [source]. Recomputed on every
     * URL edit by the parent dialog; drives the inline "this extension will be used" notice
     * so the user learns the outcome before pressing Fetch.
     */
    var detectedHandlers by mutableStateOf<List<LoadedResolver>>(emptyList())

    // ── HLS / remote-archive inspection ────────────────────────────────────────
    // These are only populated after a validated inspection (never by filename alone): the dialog
    // probes an .m3u8 or .zip-shaped link, and the engine's verdict decides what step 2 renders and
    // what typed [org.blaze.download.api.DownloadSource] submit() queues.

    /** Which review body step 2 renders once inspection has classified the source. */
    enum class ReviewedKind { PLAIN, HLS, ARCHIVE }

    var reviewedKind by mutableStateOf(ReviewedKind.PLAIN)

    /** The `.m3u8` inspection verdict, or null while nothing has been inspected (or it was rejected). */
    var hlsInspection by mutableStateOf<org.blaze.download.api.HlsInspectionDto?>(null)

    /** The master-playlist variant the user picked; -1 lets the engine choose the highest bandwidth. */
    var selectedHlsVariant by mutableStateOf(-1)

    /**
     * Whether the user acknowledged the potential-DRM warning for this attempt. It gates only what
     * gets reported back — acknowledging never unlocks DRM circumvention or key extraction.
     */
    var drmAcknowledged by mutableStateOf(false)

    /** True while the potential-DRM warning is on screen, awaiting Continue or Cancel. */
    var showDrmWarning by mutableStateOf(false)

    /** The remote-ZIP inspection result, or null when nothing has been inspected (or it was rejected). */
    var archiveInspection by mutableStateOf<org.blaze.download.api.ArchiveInspectionDto?>(null)

    /** Names of the archive members the user chose to retrieve; empty means "the whole archive". */
    var selectedArchiveEntries by mutableStateOf<Set<String>>(emptySet())

    /**
     * Explicit opt-in to download the whole archive, required when selective retrieval is impossible
     * (the host will not serve byte ranges) so a large fetch is never silently triggered by "Download".
     */
    var archiveDownloadAllAcknowledged by mutableStateOf(false)

    /** True when the parsed archive actually supports picking individual members (ranges are served). */
    val archiveCanSelect: Boolean
        get() = (archiveInspection as? org.blaze.download.api.ArchiveInspectionDto.Browsable)?.selectiveSupported == true

    /**
     * Set when the archive parsed but selective retrieval is impossible: holds the reason so the dialog
     * can offer an explicit, user-confirmed full-archive download rather than silently fetching it all.
     */
    var pendingArchiveFullDownloadReason by mutableStateOf<String?>(null)

    val batchItems = mutableStateListOf<BatchDownloadItem>()
    val isBatch: Boolean get() = batchItems.isNotEmpty()

    val source: String get() = url.text.trim()

    /** The URL actually handed to the engine: a resolved direct link when present. */
    val effectiveSource: String get() = resolvedDirectUrl ?: source

    /** User credentials plus any headers the resolved link demanded; what the transfer is sent. */
    val effectiveCredentials: Map<String, String> get() = credentials + resolvedHeaders

    val canFetch: Boolean get() = source.isNotEmpty() && (isBatch || source.isSupportedSource())
    val looksSupported: Boolean get() = source.isEmpty() || isBatch || source.isSupportedSource()

    /**
     * True when the typed link is an http(s) page rather than a file — the shape that needs a
     * handler to dig a download out of it. Batch lists are exempt: each of their entries is judged
     * on its own.
     */
    val looksLikeWebPage: Boolean get() = !isBatch && isHttpSource(source) && looksLikeWebPageLink(source)

    /** Total size of everything about to be queued, or null while any part of it is unknown. */
    val announcedBytes: Long?
        get() = if (isBatch) {
            val selected = batchItems.filter { it.isSelected }
            val sizes = selected.mapNotNull { it.metadata?.totalSize }
            if (selected.isEmpty() || sizes.size != selected.size) null else sizes.sum()
        } else when (reviewedKind) {
            // An HLS stream reports the engine's bitrate x duration estimate for the chosen variant.
            ReviewedKind.HLS -> hlsInspection?.estimatedTotalBytes
            // An archive reports the uncompressed size of just the members the user selected (all of
            // them when nothing is ticked, which is the whole-archive case).
            ReviewedKind.ARCHIVE -> archiveSelectedBytes
            ReviewedKind.PLAIN -> metadata?.totalSize
        }

    /**
     * Uncompressed bytes of the selected archive members, or the whole archive's total when no
     * selection has been made. Null until inspection has parsed the tree.
     */
    val archiveSelectedBytes: Long?
        get() {
            val browsable = (archiveInspection as? org.blaze.download.api.ArchiveInspectionDto.Browsable)
                ?: return null
            return if (selectedArchiveEntries.isEmpty()) {
                browsable.totalUncompressedBytes.takeIf { it > 0 }
            } else {
                browsable.entries
                    .filter { it.name in selectedArchiveEntries && !it.isDirectory }
                    .sumOf { it.uncompressedSize }
            }
        }

    /** True when a selective archive retrieval is on the table and something is actually ticked. */
    val archiveSelectiveChosen: Boolean
        get() = archiveInspection is org.blaze.download.api.ArchiveInspectionDto.Browsable &&
                selectedArchiveEntries.isNotEmpty()

    /** Bytes the destination cannot hold, or null when there is room or the question can't be answered. */
    val spaceShortBy: Long? get() = destinationStatus.spaceShortBy(announcedBytes)

    /** A destination that is certain to fail: unwritable, or too small for the announced file. */
    val destinationBlocked: Boolean get() = destinationStatus.isError || spaceShortBy != null

    fun resetMetadata() {
        metadata = null
        metadataUnresolved = false
        preparedTorrentFile = null
        selectedFileIndices = emptySet()
        batchItems.clear()
        resolvedDirectUrl = null
        resolvedHeaders = emptyMap()
        resolvedLinks = emptyList()
        showLinkChoice = false
        handlerChoices = emptyList()
        fetchError = null
        detectedHandlers = emptyList()
        pendingPlainPageSource = null
        // A source edit must drop every inspection verdict and choice so a stale HLS variant or
        // archive selection can never leak into a different download.
        reviewedKind = ReviewedKind.PLAIN
        hlsInspection = null
        selectedHlsVariant = -1
        drmAcknowledged = false
        showDrmWarning = false
        archiveInspection = null
        selectedArchiveEntries = emptySet()
        archiveDownloadAllAcknowledged = false
        pendingArchiveFullDownloadReason = null
    }

    private fun String.isSupportedSource(): Boolean {
        val s = trim().lowercase()
        return s.startsWith("http://") ||
                s.startsWith("https://") ||
                isTorrentFamilySource(this)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Source-loading helpers, shared by the input view ("Add from File", paste) and
// the dialog itself (a dropped/external link list arrives as an initialSource).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Builds the typed [DownloadSource] the dialog queues after an inspection classified the source, or
 * null when the review is a plain URL/torrent (which keeps going through the bare-URL path). This is
 * the single place the user's validated choices are folded into a request: an HLS pick carries the
 * chosen variant and the DRM acknowledgement (which only gates reporting, never an extra mechanism),
 * and an archive carries exactly the members selected — null selection meaning the whole archive.
 */
internal fun typedDownloadSource(state: AddDownloadState): DownloadSource? = when (state.reviewedKind) {
    AddDownloadState.ReviewedKind.HLS ->
        DownloadSource.Hls(
            url = state.effectiveSource,
            variantIndex = state.selectedHlsVariant,
            drmAcknowledged = state.drmAcknowledged,
        )

    AddDownloadState.ReviewedKind.ARCHIVE -> DownloadSource.RemoteArchive(
        url = state.effectiveSource,
        // A non-empty tick set retrieves just those members; an empty set is the whole-archive fallback.
        selectedEntries = if (state.archiveSelectiveChosen) state.selectedArchiveEntries.sorted() else null,
    )

    AddDownloadState.ReviewedKind.PLAIN -> null
}

internal fun AddDownloadState.clearSource() {
    batchItems.clear()
    url = TextFieldValue("")
    resetMetadata()
}

internal fun AddDownloadState.setSource(urls: List<String>, batchLabel: String) {
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
internal fun AddDownloadState.applyPastedText(
    raw: String,
    d: BlazeStrings.Downloads.Dialogs,
) {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    setSource(lines, d.linkCount(lines.size))
}

/** Returns an error message, or null on success. */
internal fun AddDownloadState.loadSourceFile(
    path: Path,
    d: BlazeStrings.Downloads.Dialogs,
): String? {
    if (!ExternalOpenParser.isLinkListFile(path)) {
        // .TORRENT and friends: use the path as the source
        setSource(listOf(path.toString()), "")
        return null
    }
    val urls = runCatching { File(path.toString()).readLines() }
        .getOrElse { return d.sourceFileReadFailed(it.message) }
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") } // allow comments in link lists
        .distinct()

    if (urls.isEmpty()) return d.emptyLinkFile
    setSource(urls, path.fileName.toString())
    return null
}
