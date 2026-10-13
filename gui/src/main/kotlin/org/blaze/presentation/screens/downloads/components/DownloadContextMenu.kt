package org.blaze.presentation.screens.downloads.components

import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.download.api.DownloadPriority
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.components.BlazeIcons
import org.blaze.presentation.components.BlazeKeymap
import org.blaze.presentation.screens.downloads.DownloadCapabilities
import org.blaze.presentation.screens.downloads.canCancel
import org.blaze.presentation.screens.downloads.canPause
import org.blaze.presentation.screens.downloads.canResume
import org.island.ui.menu.MenuEntry
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Builds the download row's right-click menu from the row's current [DownloadState] and the
 * [DownloadCapabilities] the host exposes, so only meaningful, actually-runnable actions are
 * offered. [hideResumeForQueued] mirrors the toolbar's rule that a queued download can't be
 * individually resumed once the concurrency slots are full.
 *
 * Groups are separated with [MenuEntry.Separator]: file operations, then transport controls,
 * then source/clipboard helpers, then removal.
 *
 * The result is plain data rather than Jewel's `ContextMenuItem` list, because Blaze draws its own
 * menus ([org.blaze.presentation.components.IslandMenu]) — Jewel's submenu state machine hangs the
 * pointer once a nested level is open. Being data is also what lets one row's menu, the header's
 * Table options and the tool-window "more" menus share a single renderer, and lets each row carry the
 * accelerator from the one [BlazeKeymap] that actually listens for it.
 */
fun buildDownloadContextMenu(
    download: Download,
    capabilities: DownloadCapabilities,
    strings: BlazeStrings,
    actions: DownloadRowActions,
    hideResumeForQueued: Boolean = false,
    fileMissing: Boolean = false,
): List<MenuEntry> {
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
            add(action(labels.reDownload, AllIconsKeys.Actions.Download, on = actions.reDownload))
            divider()
        } else if (isCompleted) {
            if (capabilities.canOpenFiles) {
                add(
                    action(
                        labels.openFile,
                        AllIconsKeys.General.OpenDisk,
                        on = actions.openFile,
                        key = BlazeKeymap.Action.OpenSelected,
                    ),
                )
            }
            if (capabilities.canRevealInFolder) {
                add(action(labels.showInFolder, AllIconsKeys.Nodes.Folder, on = actions.showInFolder))
            }
            divider()
        }

        // ── Transport controls ────────────────────────────────────────────────
        when {
            download.canPause() ->
                add(
                    action(
                        labels.pause,
                        AllIconsKeys.Actions.Pause,
                        on = actions.pause,
                        key = BlazeKeymap.Action.TogglePause,
                    ),
                )

            isResumable ->
                add(
                    action(
                        labels.resume,
                        AllIconsKeys.Actions.Resume,
                        on = actions.resume,
                        key = BlazeKeymap.Action.TogglePause,
                    ),
                )

            isFailed ->
                add(action(labels.retry, AllIconsKeys.Actions.Restart, on = actions.retry))
        }
        if (isCancellable) {
            add(action(labels.cancel, BlazeIcons.Stop, on = actions.cancel))
        }

        // ── Priority (only while the transfer still has queue time ahead of it) ──
        if (canSetPriority) {
            val p = strings.downloads.priority
            add(
                MenuEntry.Submenu(
                    label = p.label,
                    children = DownloadPriority.entries.map { level ->
                        MenuEntry.Action(
                            label = level.label(p),
                            // The IDE marks the current choice in the icon column instead of greying
                            // it out, so the group reads as a radio set rather than as dead rows.
                            checked = download.priority == level,
                            onClick = { actions.setPriority(level) },
                        )
                    },
                )
            )
        }
        divider()

        // ── Source & clipboard helpers ────────────────────────────────────────
        if (isWebLink && capabilities.canBrowseLinks) {
            add(action(labels.openSourceLink, AllIconsKeys.General.Web, on = actions.openSourceLink))
        }
        if (capabilities.canCopy && download.url.isNotBlank()) {
            add(action(labels.copyLink, AllIconsKeys.General.Copy, on = actions.copyLink))
        }
        if (capabilities.canCopy) {
            add(action(labels.copyFilePath, AllIconsKeys.General.InlineCopy, on = actions.copyFileLocation))
        }
        divider()

        // ── Details (always available) ────────────────────────────────────────
        add(
            action(
                labels.showDetails,
                AllIconsKeys.General.ShowInfos,
                on = actions.showDetails,
                key = BlazeKeymap.Action.ToggleDetails,
            ),
        )
        // With the per-row checkboxes gone, the context menu (and the header gear) is where "Select
        // All" lives, so a multi-row bulk action stays reachable without the keyboard.
        add(
            action(
                strings.downloads.selection.selectAll,
                AllIconsKeys.Actions.Selectall,
                on = actions.selectAll,
                key = BlazeKeymap.Action.SelectAll,
            ),
        )
        divider()

        // ── Removal ───────────────────────────────────────────────────────────
        // Nothing is left on disk for a gone file, so this is a plain clear rather than the
        // remove-or-delete choice a still-present file gets.
        if (isGone) {
            add(action(labels.clear, AllIconsKeys.Actions.GC, on = actions.clear))
        } else {
            add(
                action(
                    labels.remove,
                    AllIconsKeys.Actions.GC,
                    on = actions.remove,
                    key = BlazeKeymap.Action.Remove,
                ),
            )
        }
    }
}

/** Appends a section separator only when it would sit between two real items. */
private fun MutableList<MenuEntry>.divider() {
    if (isNotEmpty() && last() != MenuEntry.Separator) add(MenuEntry.Separator)
}

/**
 * One leaf row. [key] is the keymap action whose accelerator this row performs — print it only if some
 * handler really listens for it, which is the whole point of taking it from [BlazeKeymap] rather than
 * typing a string.
 */
private fun action(
    label: String,
    icon: IconKey,
    enabled: Boolean = true,
    key: BlazeKeymap.Action = BlazeKeymap.Action.None,
    on: () -> Unit,
) = MenuEntry.Action(
    label = label,
    icon = icon,
    accelerator = BlazeKeymap.displayValue(key),
    enabled = enabled,
    onClick = on,
)

/** Localized name for a priority level, shared by the row menu, badge and add dialog. */
internal fun DownloadPriority.label(strings: BlazeStrings.Downloads.Priority): String = when (this) {
    DownloadPriority.IDLE -> strings.idle
    DownloadPriority.LOW -> strings.low
    DownloadPriority.NORMAL -> strings.normal
    DownloadPriority.HIGH -> strings.high
}
