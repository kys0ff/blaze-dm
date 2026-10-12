package org.blaze.engine.api

import java.nio.file.Path

sealed interface TorrentSource {
    /**
     * A local `.torrent` file. [origin] records the [Magnet] or [Url] source this file was cached
     * from, when it was: the engine swaps a live magnet/URL for its cached metainfo so transfers can
     * restart without re-resolving the swarm, but the *user-facing* source locator must keep pointing
     * at what they actually queued (the magnet URI / torrent URL), not the internal cache path.
     */
    data class File(val path: Path, val origin: TorrentSource? = null) : TorrentSource

    /** A `magnet:` URI; metadata arrives from the swarm over BEP 9. */
    data class Magnet(val uri: String) : TorrentSource

    /**
     * An http(s) URL that serves a `.torrent` file. `bt` fetches and parses the metainfo itself,
     * and the engine caches the fetched file afterwards, so a torrent URL behaves like a magnet
     * whose metainfo happens to live on a web server instead of in the DHT.
     */
    data class Url(val url: String) : TorrentSource
}
