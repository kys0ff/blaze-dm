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
        /**
         * Connections this download should use. Zero means "whatever the global setting says";
         * a positive value is an explicit per-download choice that wins in both directions,
         * because "fetch this stubborn server with one connection" has to be expressible.
         */
        val segmentCount: Int = 0
    ) : DownloadRequest

    data class Torrent(
        override val name: String,
        val torrentSource: TorrentSource,
        override val destination: Path,
        val fileIndices: List<Int>? = null
    ) : DownloadRequest
}