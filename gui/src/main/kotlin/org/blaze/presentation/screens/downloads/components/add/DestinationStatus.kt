package org.blaze.presentation.screens.downloads.components.add

import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.util.formatSize
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the save folder currently promises, discovered from the file system.
 *
 * Two questions are answered separately on purpose: whether the folder can be written to at all,
 * and whether it has room for a download of the announced size. The first is known while the URL is
 * being typed, the second only once metadata has resolved the file's size.
 */
sealed interface DestinationStatus {
    data object Empty : DestinationStatus
    data object Invalid : DestinationStatus
    data object NotADirectory : DestinationStatus
    data object NotWritable : DestinationStatus
    data object WillCreate : DestinationStatus
    data class Ready(val freeBytes: Long) : DestinationStatus

    val isError: Boolean
        get() = this == Invalid || this == NotADirectory || this == NotWritable
}

/** Touches the disk, so callers run it off the UI thread. Never throws: bad paths come back as [DestinationStatus.Invalid]. */
fun resolveDestinationStatus(raw: String): DestinationStatus {
    val text = raw.trim()
    if (text.isEmpty()) return DestinationStatus.Empty
    val path = runCatching { Path.of(text) }.getOrNull() ?: return DestinationStatus.Invalid
    return when {
        Files.isDirectory(path) ->
            if (Files.isWritable(path)) {
                DestinationStatus.Ready(runCatching { path.toFile().usableSpace }.getOrDefault(-1L))
            } else {
                DestinationStatus.NotWritable
            }

        Files.exists(path) -> DestinationStatus.NotADirectory
        else -> DestinationStatus.WillCreate
    }
}

/**
 * How many bytes of the announced size do not fit, or null when the question cannot be answered
 * (unknown size, unknown free space) or everything fits.
 */
fun DestinationStatus.spaceShortBy(requiredBytes: Long?): Long? {
    val free = (this as? DestinationStatus.Ready)?.freeBytes ?: return null
    val needed = requiredBytes ?: return null
    if (free < 0 || needed <= 0 || needed <= free) return null
    return needed - free
}

/** Wording for "the drive cannot hold this", or null when everything fits. */
fun DestinationStatus.notEnoughSpaceText(requiredBytes: Long?, strings: BlazeStrings): String? {
    val short = spaceShortBy(requiredBytes) ?: return null
    val free = (this as? DestinationStatus.Ready)?.freeBytes ?: return null
    return strings.downloads.dialogs.notEnoughSpace(
        formatSize(free + short, strings),
        formatSize(free, strings)
    )
}
