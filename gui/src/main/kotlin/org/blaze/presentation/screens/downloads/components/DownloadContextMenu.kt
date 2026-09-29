package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.ContextMenuItem
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.download.api.DownloadPriority
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.downloads.DownloadCapabilities
import org.blaze.presentation.screens.downloads.canCancel
import org.blaze.presentation.screens.downloads.canPause
import org.blaze.presentation.screens.downloads.canResume
import org.jetbrains.jewel.ui.component.ContextMenuDivider
import org.jetbrains.jewel.ui.component.ContextMenuItemOption
import org.jetbrains.jewel.ui.component.ContextSubmenu
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Builds the download row's right-click menu from the row's current [DownloadState] and the
 * [DownloadCapabilities] the host exposes, so only meaningful, actually-runnable actions are
 * offered. [hideResumeForQueued] mirrors the toolbar's rule that a queued download can't be
 * individually resumed once the concurrency slots are full.
 *
 * Groups are separated with [ContextMenuDivider]: file operations, then transport controls,
 * then source/clipboard helpers, then removal.
 */
fun buildDownloadContextMenu(
    download: Download,
    capabilities: DownloadCapabilities,
    strings: BlazeStrings,
    actions: DownloadRowActions,
    hideResumeForQueued: Boolean = false,
    fileMissing: Boolean = false,
): List<ContextMenuItem> {
    val labels = strings.downloads.actions
    val state = download.state
    val isCompleted = state == DownloadState.COMPLETED || state == DownloadState.SEEDING
    // A finished row whose file has since been deleted can neither be opened nor revealed; it can
    // only be fetched again or cleared off the list.
    val isGone = isCompleted && fileMissing
    // Transport eligibility comes from the shared predicates so the menu, the row buttons and the
    // bulk cluster read the same rule: a finished row is neither resumable nor cancellable.
    val isResumable = download.canResume(hideResumeForQueued)
    val isCancellable = download.canCancel()
    val isFailed = state == DownloadState.FAILED
    // Priority only steers work that still has queue time ahead of it; a terminal row can't be re-ranked.
    val canSetPriority = state == DownloadState.QUEUED ||
        state == DownloadState.DOWNLOADING || state == DownloadState.PAUSED
    val isWebLink = download.url.startsWith("http://", ignoreCase = true) ||
        download.url.startsWith("https://", ignoreCase = true)

    return buildList {
        // ── Finished file: open it or find it on disk ─────────────────────────
        if (isGone) {
            add(option(labels.reDownload, AllIconsKeys.Actions.Download, actions.reDownload))
            divider()
        } else if (isCompleted) {
            if (capabilities.canOpenFiles) {
                add(option(labels.openFile, AllIconsKeys.General.OpenDisk, actions.openFile))
            }
            if (capabilities.canRevealInFolder) {
                add(option(labels.showInFolder, AllIconsKeys.Nodes.Folder, actions.showInFolder))
            }
            divider()
        }

        // ── Transport controls ────────────────────────────────────────────────
        when {
            download.canPause() ->
                add(option(labels.pause, AllIconsKeys.Actions.Pause, actions.pause))

            isResumable ->
                add(option(labels.resume, AllIconsKeys.Actions.Resume, actions.resume))

            isFailed ->
                add(option(labels.retry, AllIconsKeys.Actions.Restart, actions.retry))
        }
        if (isCancellable) {
            add(option(labels.cancel, AllIconsKeys.Actions.Cancel, actions.cancel))
        }

        // ── Priority (only while the transfer still has queue time ahead of it) ──
        if (canSetPriority) {
            val p = strings.downloads.priority
            add(
                ContextSubmenu(p.label) {
                    DownloadPriority.entries.map { level ->
                        ContextMenuItemOption(
                            icon = null,
                            enabled = download.priority != level,
                            label = level.label(p),
                            action = { actions.setPriority(level) },
                        )
                    }
                }
            )
        }
        divider()

        // ── Source & clipboard helpers ────────────────────────────────────────
        if (isWebLink && capabilities.canBrowseLinks) {
            add(option(labels.openSourceLink, AllIconsKeys.General.Web, actions.openSourceLink))
        }
        if (capabilities.canCopy && download.url.isNotBlank()) {
            add(option(labels.copyLink, AllIconsKeys.General.Copy, actions.copyLink))
        }
        if (capabilities.canCopy) {
            add(option(labels.copyFilePath, AllIconsKeys.General.InlineCopy, actions.copyFileLocation))
        }
        divider()

        // ── Details (always available) ────────────────────────────────────────
        add(option(labels.showDetails, AllIconsKeys.General.ShowInfos, actions.showDetails))
        // With the per-row checkboxes gone, the context menu (and the header gear) is where "Select
        // All" lives, so a multi-row bulk action stays reachable without the keyboard.
        add(option(strings.downloads.selection.selectAll, AllIconsKeys.Actions.Selectall, actions.selectAll))
        divider()

        // ── Removal ───────────────────────────────────────────────────────────
        // Nothing is left on disk for a gone file, so this is a plain clear rather than the
        // remove-or-delete choice a still-present file gets.
        if (isGone) {
            add(option(labels.clear, AllIconsKeys.Actions.GC, actions.clear))
        } else {
            add(option(labels.remove, AllIconsKeys.Actions.GC, actions.remove))
        }
    }
}

/** Appends a section separator only when it would sit between two real items. */
private fun MutableList<ContextMenuItem>.divider() {
    if (isNotEmpty() && last() != ContextMenuDivider) add(ContextMenuDivider)
}

private fun option(
    label: String,
    icon: IconKey,
    action: () -> Unit,
) = ContextMenuItemOption(icon = icon, label = label, action = action)

/** Localized name for a priority level, shared by the row menu, badge and add dialog. */
internal fun DownloadPriority.label(strings: BlazeStrings.Downloads.Priority): String = when (this) {
    DownloadPriority.IDLE -> strings.idle
    DownloadPriority.LOW -> strings.low
    DownloadPriority.NORMAL -> strings.normal
    DownloadPriority.HIGH -> strings.high
}
