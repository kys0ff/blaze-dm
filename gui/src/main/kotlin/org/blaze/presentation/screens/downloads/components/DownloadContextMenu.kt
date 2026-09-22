package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.ContextMenuItem
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.downloads.DownloadCapabilities
import org.jetbrains.jewel.ui.component.ContextMenuDivider
import org.jetbrains.jewel.ui.component.ContextMenuItemOption
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
): List<ContextMenuItem> {
    val labels = strings.downloads.actions
    val state = download.state
    val isCompleted = state == DownloadState.COMPLETED || state == DownloadState.SEEDING
    val isResumable = state == DownloadState.PAUSED ||
        (state == DownloadState.QUEUED && !hideResumeForQueued)
    val isCancellable = state == DownloadState.DOWNLOADING || state == DownloadState.SEEDING ||
        state == DownloadState.PAUSED || state == DownloadState.QUEUED
    val isFailed = state == DownloadState.FAILED
    val isWebLink = download.url.startsWith("http://", ignoreCase = true) ||
        download.url.startsWith("https://", ignoreCase = true)

    return buildList {
        // ── Finished file: open it or find it on disk ─────────────────────────
        if (isCompleted) {
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
            state == DownloadState.DOWNLOADING || state == DownloadState.SEEDING ->
                add(option(labels.pause, AllIconsKeys.Actions.Pause, actions.pause))

            isResumable ->
                add(option(labels.resume, AllIconsKeys.Actions.Resume, actions.resume))

            isFailed ->
                add(option(labels.retry, AllIconsKeys.Actions.Restart, actions.retry))
        }
        if (isCancellable) {
            add(option(labels.cancel, AllIconsKeys.Actions.Cancel, actions.cancel))
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

        // ── Removal ───────────────────────────────────────────────────────────
        add(option(labels.remove, AllIconsKeys.Actions.GC, actions.remove))
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
