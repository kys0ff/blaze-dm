package org.blaze.download.api

/** Which protocol family a download's source resolves to. */
enum class SourceKind {
    HTTP,
    HTTPS,
    MAGNET,
    TORRENT_FILE,
}
