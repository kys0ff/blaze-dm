package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.osintegration.ExternalOpenRequest
import org.blaze.osintegration.classifyForDropOrNull
import org.island.shell.IslandDropHint
import org.island.shell.IslandWindowDrop
import org.island.shell.rememberIslandWindowDrop
import org.island.ui.notifications.NotificationsState
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Blaze's whole-window drop target - a thin consumer of the shell's [rememberIslandWindowDrop].
 *
 * Every hovered item and every drop is classified through the shared [ExternalOpenParser] - the
 * same validation boundary as argv and the single-instance IPC channel, with the full drop
 * vocabulary: magnets, web links, `.torrent` files, link-list text files and candidate partial
 * downloads (any existing file/folder whose portability the engine decides later). Accepted
 * requests land in the [ExternalOpenInbox]; from there the existing external-open flow takes
 * over. This surface never talks to the engine directly, so a drop can never bypass the dialog's
 * destination/conflict checks.
 *
 * The overlay title, unsupported title, hint rows and post-drop balloon wording all come from
 * Blaze's own strings bundle (through [BlazeStrings.downloads.drop]) - the shell's generic
 * `IslandUiStrings` fallback would otherwise say "Drop to add" instead of "Drop to add downloads".
 */
@Composable
fun rememberWindowDropTarget(
    inbox: ExternalOpenInbox,
    parser: ExternalOpenParser,
    notifications: NotificationsState,
): IslandWindowDrop {
    val strings = blazeStrings
    val classify: (String) -> org.island.shell.IslandDropIntent? = { value ->
        parser.classifyForDropOrNull(value)?.let { org.island.shell.IslandDropIntent(payload = it) }
    }
    val onDropOutcome: (accepted: List<org.island.shell.IslandDropIntent>, rejectedNames: List<String>) -> Boolean =
        { accepted, rejectedNames ->
            @Suppress("UNCHECKED_CAST")
            val requests = accepted.mapNotNull { it.payload as? ExternalOpenRequest }
            if (requests.isNotEmpty()) inbox.submit(requests)
            reportDropOutcome(strings, notifications, requests.size, rejectedNames)
            // Consume the gesture when anything usable landed; a fully unusable drop bounces back
            // to the source (the balloon above explains why).
            requests.isNotEmpty()
        }
    val hints = listOf(
        IslandDropHint(strings.downloads.drop.hintTorrents, AllIconsKeys.Nodes.Folder),
        IslandDropHint(strings.downloads.drop.hintLinks, AllIconsKeys.Actions.Download),
        IslandDropHint(strings.downloads.drop.hintLinkLists, AllIconsKeys.FileTypes.Any_type),
        IslandDropHint(strings.downloads.drop.hintPartials, AllIconsKeys.FileTypes.Archive),
    )
    return rememberIslandWindowDrop(
        classify = classify,
        onDropOutcome = onDropOutcome,
        hints = hints,
    )
}

/** Post-drop balloons: what got queued, and by name what could not be used. */
private fun reportDropOutcome(
    strings: BlazeStrings,
    notifications: NotificationsState,
    queued: Int,
    rejectedNames: List<String>,
) {
    val rejected = rejectedNames.joinToString(", ") { truncateName(it) }
    when {
        queued > 0 && rejectedNames.isEmpty() ->
            notifications.info(strings.downloads.drop.queued(queued))

        queued > 0 -> {
            notifications.info(strings.downloads.drop.queued(queued))
            notifications.warning(strings.downloads.drop.rejected(rejected))
        }

        rejectedNames.isNotEmpty() ->
            notifications.error(strings.downloads.drop.rejected(rejected))

        // A payload we could not read any item from (broken flavor data, unreadable list).
        else -> notifications.error(strings.downloads.drop.nothingUsable)
    }
}

private fun truncateName(label: String): String =
    if (label.length <= 32) label else label.take(30) + "…"
