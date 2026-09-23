package org.blaze.presentation.screens.downloads

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState

/**
 * What has actually been proved about a source's ability to continue an interrupted transfer.
 *
 * [UNKNOWN] is deliberately distinct from [UNSUPPORTED]: the engine only reports an answer when a
 * response proved it, and warning a user about a server that never refused anything would be a lie
 * by omission. UI must therefore stay silent for [UNKNOWN].
 */
enum class ResumeSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

fun Download.resumeSupport(): ResumeSupport = when (resumeSupported) {
    true -> ResumeSupport.SUPPORTED
    false -> ResumeSupport.UNSUPPORTED
    null -> ResumeSupport.UNKNOWN
}

/** True when this row's source is a torrent, which resumes from its own piece bitmap. */
fun Download.isTorrentSource(): Boolean =
    url.startsWith("magnet:", ignoreCase = true) ||
        url.endsWith(".torrent", ignoreCase = true) ||
        url == "Local Torrent"

/** Still in flight (or parked mid-flight), so a decision here can cost bytes on disk. */
private val Download.isTransferable: Boolean
    get() = state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED || state == DownloadState.PAUSED

private val Download.isFinished: Boolean
    get() = state == DownloadState.COMPLETED || state == DownloadState.SEEDING

/** Whole percent already on disk, for wording a risk in terms the user sees on the row. */
fun Download.percentDone(): Int = (progress.coerceIn(0f, 1f) * 100).toInt()

/**
 * True when pausing right now would throw [Download.downloadedSize] away: the source refused ranges
 * and there is progress worth keeping. A download that is already paused has nothing left to lose.
 */
fun Download.pauseLosesProgress(): Boolean =
    state != DownloadState.PAUSED && isTransferable && downloadedSize > 0 &&
        resumeSupport() == ResumeSupport.UNSUPPORTED

/**
 * True when resuming/retrying has to restart the transfer from zero, because the source cannot be
 * continued and bytes were already fetched in the previous attempt.
 */
fun Download.resumeStartsOver(): Boolean =
    (state == DownloadState.PAUSED || state == DownloadState.FAILED) &&
        downloadedSize > 0 && resumeSupport() == ResumeSupport.UNSUPPORTED

/**
 * A torrent that is nominally running but has connected to nobody: the transfer cannot make progress
 * and no timeout will end it, so the user is the only one who can decide to stop waiting.
 */
fun Download.isWaitingForPeers(): Boolean =
    state == DownloadState.DOWNLOADING && isTorrentSource() && peers == 0 && speed == 0L

/** An in-flight download whose server will not let it survive a pause. */
fun Download.isUnresumableInProgress(): Boolean =
    isTransferable && resumeSupport() == ResumeSupport.UNSUPPORTED

/** How many rows deserve the user's attention, by reason. Drives the status-bar alert. */
data class AttentionCounts(
    val cannotResume: Int = 0,
    val waitingForPeers: Int = 0,
    val failed: Int = 0
) {
    val isEmpty: Boolean get() = cannotResume == 0 && waitingForPeers == 0 && failed == 0
}

fun List<Download>.attention(): AttentionCounts = AttentionCounts(
    cannotResume = count { it.isUnresumableInProgress() },
    waitingForPeers = count { it.isWaitingForPeers() },
    failed = count { it.state == DownloadState.FAILED }
)

/**
 * A lifecycle change worth telling the user about, discovered by comparing two snapshots of the list.
 *
 * Carries data only - the copy is produced by the view from [org.blaze.i18n.BlazeStrings], so these
 * stay translatable and this diff stays a pure function.
 */
sealed interface DownloadAdvisory {
    /** Reached COMPLETED/SEEDING while the window was open. */
    data class Completed(val download: Download) : DownloadAdvisory

    /** Left with an error; the view may delay it to skip an automatic retry's intermediate hop. */
    data class Failed(val download: Download) : DownloadAdvisory

    /** Bytes went backwards: the engine had to restart the transfer, discarding progress. */
    data class Restarted(val download: Download, val lostPercent: Int) : DownloadAdvisory
}

/**
 * Diffs two consecutive snapshots of the download list into the notices a user should hear.
 *
 * A download absent from [previous] is skipped: it was either added while the window was closed or
 * restored at startup, and neither is news. So is a download that simply disappeared (it was removed).
 */
fun diffDownloadTransitions(previous: List<Download>, current: List<Download>): List<DownloadAdvisory> {
    if (previous.isEmpty()) return emptyList()
    val before = previous.associateBy { it.id }

    return current.mapNotNull { now ->
        val then = before[now.id] ?: return@mapNotNull null
        when {
            !then.isFinished && now.isFinished -> DownloadAdvisory.Completed(now)
            then.state != DownloadState.FAILED && now.state == DownloadState.FAILED -> DownloadAdvisory.Failed(now)
            // Bytes went backwards on a transfer that was still in flight: the engine had to restart
            // it, discarding progress the user cared about. A finished row is excluded because redownloading it is the user asking for the file again, not lost work - the engine also
            // zeroes a re-queued finished task's count, so no stale 100% -> 0 drop reaches here.
            !then.isFinished && !now.isFinished &&
                then.downloadedSize > 0 && now.downloadedSize < then.downloadedSize ->
                // Never say "0%": a dropped counter that had already moved is still a loss worth naming.
                DownloadAdvisory.Restarted(now, then.percentDone().coerceAtLeast(1))
            else -> null
        }
    }
}

/**
 * Whether adding [url] into [savePath] duplicates something already on the list, so the dialog can
 * warn instead of silently queueing a second copy of the same file.
 *
 * Comparison ignores a trailing slash on the folder and the query-less form of an http(s) URL, which
 * is how the same resource tends to arrive twice from a browser.
 */
fun List<Download>.findDuplicateOf(url: String, savePath: String): Download? {
    val wantedSource = url.normalizeSource()
    if (wantedSource.isEmpty()) return null
    val wantedFolder = savePath.normalizeFolder()

    return firstOrNull { existing ->
        existing.url.normalizeSource() == wantedSource && existing.savePath.normalizeFolder() == wantedFolder
    }
}

private fun String.normalizeSource(): String = trim().removeSuffix("/")

private fun String.normalizeFolder(): String = trim().removeSuffix("/")
