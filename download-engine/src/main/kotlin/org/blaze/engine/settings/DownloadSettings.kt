package org.blaze.engine.settings

import kotlinx.serialization.Serializable

@Serializable
enum class FileConflictBehavior {
    ASK,
    OVERWRITE,
    SKIP,
    RENAME
}

@Serializable
data class DownloadSettings(
    val maxConcurrentDownloads: Int = 4,
    val maxConnectionsPerDownload: Int = 4,
    val globalSpeedLimitEnabled: Boolean = false,
    val globalSpeedLimitKbps: Long = 10240, // default 10 MB/s
    val autoRetryFailed: Boolean = true,
    val maxRetries: Int = 3,
    val retryDelaySeconds: Int = 5,
    val resumeDownloadsOnStartup: Boolean = true,
    val startQueuedOnStartup: Boolean = true,
    val defaultDownloadDir: String = System.getProperty("user.home") + "/Downloads",
    val askWhereToSave: Boolean = false,
    val fileConflictBehavior: FileConflictBehavior = FileConflictBehavior.ASK
)
