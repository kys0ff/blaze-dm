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
        /**
         * The folder that holds this torrent's content - not the directory to put that folder in.
         * Files land inside it exactly as the torrent describes them, with no extra level named
         * after the torrent, and it is what the portable marker travels with and the UI reveals.
         */
        override val destination: Path,
        val fileIndices: List<Int>? = null
    ) : DownloadRequest

    /**
     * An HTTP Live Streaming resource. [playlistUrl] is a master or media `.m3u8`; the engine follows
     * master→variant→media itself, so the caller never guesses from a file extension. [destination] is
     * the single output file the assembled stream is written to.
     *
     * @param variantIndex master-playlist variant to select; -1 means "highest advertised bandwidth".
     * @param drmAcknowledged the user already accepted a potential-DRM warning. It only ever lets the
     *   engine report *why* a protected stream is refused — it never unlocks DRM circumvention.
     */
    data class Hls(
        override val name: String,
        val playlistUrl: String,
        override val destination: Path,
        val headers: Map<String, String> = emptyMap(),
        val variantIndex: Int = -1,
        val drmAcknowledged: Boolean = false,
    ) : DownloadRequest

    /**
     * A remote (or local) ZIP archive. When [selectedEntries] is non-null and the source honours byte
     * ranges, only those entries are fetched; otherwise the whole archive is downloaded to
     * [destination]. [destination] is the extraction folder for selective mode and the archive file for
     * full-download mode, matching how the caller resolved it.
     */
    data class Archive(
        override val name: String,
        val url: String,
        override val destination: Path,
        val headers: Map<String, String> = emptyMap(),
        val selectedEntries: List<String>? = null,
    ) : DownloadRequest
}