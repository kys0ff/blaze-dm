package org.blaze.engine.api

import java.util.UUID

@JvmInline
value class DownloadId(val value: String) {
    companion object {
        fun generate() = DownloadId(UUID.randomUUID().toString())
    }
}
