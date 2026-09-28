package org.blaze.download.api

/** Which protocol family a download's source resolves to. */
enum class SourceKind {
    HTTP,
    HTTPS,
    MAGNET,
    TORRENT_FILE,

    /** An http(s) URL that serves a `.torrent` metainfo; the transfer itself is a torrent. */
    TORRENT_URL,
}
