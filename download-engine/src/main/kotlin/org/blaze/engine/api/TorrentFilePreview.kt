package org.blaze.engine.api

import java.nio.file.Path

/**
 * The result of fetching a web-served `.torrent` file so it can be previewed and started as a local
 * torrent: the metainfo [torrentFile] the engine saved to its cache, plus the [metadata] parsed from it.
 *
 * The caller adds the download from [torrentFile] (a [TorrentSource.File]) rather than re-fetching the
 * URL, so the transfer behaves exactly like one the user picked from disk.
 */
data class TorrentFilePreview(
    val torrentFile: Path,
    val metadata: DownloadMetadata,
)
