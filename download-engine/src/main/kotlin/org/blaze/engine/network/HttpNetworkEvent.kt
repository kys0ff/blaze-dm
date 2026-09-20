package org.blaze.engine.network

import org.blaze.engine.api.DownloadError

sealed interface HttpNetworkEvent {
    data class Headers(val isResumed: Boolean, val contentLength: Long) : HttpNetworkEvent
    data class Chunk(val data: ByteArray, val length: Int) : HttpNetworkEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Chunk

            if (length != other.length) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = length
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data object Completed : HttpNetworkEvent
    data class Error(val error: DownloadError) : HttpNetworkEvent
}
