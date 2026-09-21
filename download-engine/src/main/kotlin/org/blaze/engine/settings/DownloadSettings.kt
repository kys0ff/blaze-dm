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
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

@Serializable
data class DownloadSettings(
    val maxConcurrentDownloads: Int = 4,
    val maxConnectionsPerDownload: Int = 4,
    val globalSpeedLimitEnabled: Boolean = false,
    val globalSpeedLimitKbps: Long = 10240, // default 10 MB/s
    val autoRetryFailed: Boolean = true,
    val maxRetries: Int = 3,
    val retryDelaySeconds: Int = 5,
    val exponentialBackoff: Boolean = false,
    val resumeDownloadsOnStartup: Boolean = true,
    val startQueuedOnStartup: Boolean = true,
    val defaultDownloadDir: String = System.getProperty("user.home") + "/Downloads",
    val askWhereToSave: Boolean = true,
    val fileConflictBehavior: FileConflictBehavior = FileConflictBehavior.ASK,
    val httpUserAgent: String = DEFAULT_USER_AGENT,
    val maxRedirects: Int = 5,
    val maxPeerConnections: Int = 200,
    val enableSeeding: Boolean = false,
    val seedTimeLimitMinutes: Int = 30,
    val themeMode: ThemeMode = ThemeMode.DARK
)
