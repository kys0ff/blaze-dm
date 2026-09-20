package org.blaze.engine.api

import java.nio.file.Path

sealed interface TorrentSource {
    data class File(val path: Path) : TorrentSource
    data class Magnet(val uri: String) : TorrentSource
}