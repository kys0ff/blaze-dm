package org.blaze.download.api

/** Which protocol family a download's source resolves to. */
enum class SourceKind {
    HTTP,
    HTTPS,
    MAGNET,
    TORRENT_FILE,

    /** An http(s) URL that serves a `.torrent` metainfo; the transfer itself is a torrent. */
    TORRENT_URL,

    /** An HLS (`.m3u8`) playlist resource assembled into a single media file. */
    HLS,

    /** A remote ZIP archive, downloaded whole or with selectively retrieved entries. */
    ZIP_ARCHIVE,
}
