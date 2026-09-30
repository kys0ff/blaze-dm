package org.blaze.presentation.screens.downloads.components.add

import org.blaze.domain.repository.DownloadMetadata
import org.blaze.resolver.api.ResolvedLink

/**
 * What the user's pick from a multi-link resolution means for the add flow.
 *
 * A handler that returned exactly one link (or one chosen out of several) continues the
 * normal single-download review; a chosen set becomes a batch reviewed on the same screen
 * the "Add from File" list already uses.
 */
sealed interface ResolvedPick {
    /** Nothing was selected — the dialog stays open, no download is prepared. */
    data object None : ResolvedPick

    /** One link: fill the single-download review step. */
    data class Single(val link: ResolvedLink) : ResolvedPick

    /** Several links: expand into batch rows for the review list. */
    data class Many(val links: List<ResolvedLink>) : ResolvedPick
}

/** Decides the review shape from the selected links, so the dialog has one branch point. */
fun pickResolvedLinks(selected: List<ResolvedLink>): ResolvedPick = when (selected.size) {
    0 -> ResolvedPick.None
    1 -> ResolvedPick.Single(selected.first())
    else -> ResolvedPick.Many(selected)
}

/**
 * The metadata a [ResolvedLink] can announce without a network probe: the handler's
 * suggested name (falling back to the last URL segment before any query, then a default)
 * and its size when known. A resolved link never carries a torrent-style file list, so
 * [files] is null — the single-download path then probes for the real size/headers.
 */
fun resolvedLinkToMetadata(link: ResolvedLink, fallbackName: String = "download"): DownloadMetadata {
    val name = link.fileName
        ?: runCatching { java.net.URI(link.directUrl).path?.substringAfterLast('/') }
            .getOrNull()
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }
        ?: fallbackName
    return DownloadMetadata(name = name, totalSize = link.sizeBytes, files = null)
}

/**
 * Batch rows for a multi-link pick, each pre-seeded with its announced metadata so the
 * review list shows a name and size without a probe the resolution already answered.
 */
fun resolvedLinksToBatchItems(links: List<ResolvedLink>): List<BatchDownloadItem> =
    links.map { BatchDownloadItem(url = it.directUrl, initialMetadata = resolvedLinkToMetadata(it)) }
