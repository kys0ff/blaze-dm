package org.blaze.engine.api

import java.nio.file.Path

sealed interface DownloadRequest {
    val name: String
    val destination: Path

    data class Http(
        override val name: String,
        val url: String,
        override val destination: Path,
        val headers: Map<String, String> = emptyMap(),
        val segmentCount: Int = 1
    ) : DownloadRequest

    data class Torrent(
        override val name: String,
        val torrentSource: TorrentSource,
        override val destination: Path
    ) : DownloadRequest
}