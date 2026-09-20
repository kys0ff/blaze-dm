package org.blaze.engine.network

import org.blaze.engine.api.DownloadError

sealed interface TorrentNetworkEvent {
    data class MetadataResolved(val name: String, val totalBytes: Long, val metadataBytes: ByteArray?) : TorrentNetworkEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as MetadataResolved

            if (totalBytes != other.totalBytes) return false
            if (name != other.name) return false
            if (!metadataBytes.contentEquals(other.metadataBytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = totalBytes.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + (metadataBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val peers: Int,
        val piecesComplete: Int,
        val piecesTotal: Int,
        val piecesRemaining: Int
    ) : TorrentNetworkEvent
    data object Completed : TorrentNetworkEvent
    data class Error(val error: DownloadError) : TorrentNetworkEvent
}