package org.blaze.engine.api

import java.nio.file.Path

sealed interface TorrentSource {
    data class File(val path: Path) : TorrentSource

    /** A `magnet:` URI; metadata arrives from the swarm over BEP 9. */
    data class Magnet(val uri: String) : TorrentSource

    /**
     * An http(s) URL that serves a `.torrent` file. `bt` fetches and parses the metainfo itself,
     * and the engine caches the fetched file afterwards, so a torrent URL behaves like a magnet
     * whose metainfo happens to live on a web server instead of in the DHT.
     */
    data class Url(val url: String) : TorrentSource
}
