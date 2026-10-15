package org.blaze.engine.settings

import kotlinx.serialization.Serializable
import org.blaze.engine.api.DownloadPriority

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

/** Application-wide log verbosity. Mirrors the levels understood by the logging backend. */
@Serializable
enum class LogLevel {
    OFF,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE
}

const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

@Serializable
data class DownloadSettings(
    val maxConcurrentDownloads: Int = 4,
    val maxConnectionsPerDownload: Int = 4,
    val httpAccelerationEnabled: Boolean = true,

    /**
     * Priority given to a new download when the client does not choose one. Defaults to NORMAL, so
     * out of the box every transfer is ranked equally and the queue stays strictly first-in-first-out.
     */
    val defaultDownloadPriority: DownloadPriority = DownloadPriority.NORMAL,

    /** Below this size the extra requests of a segmented transfer cost more than they save. */
    val httpMinParallelSizeBytes: Long = 4L * 1024 * 1024,

    /** Preferred work unit handed to a connection; the planner shrinks it for small files. */
    val httpChunkSizeMb: Int = 8,

    /** A connection idle for this long while others progress gets dropped. */
    val httpStalledConnectionSeconds: Int = 30,

    /**
     * Shed concurrent connections when a host pushes back with 429 / 503, and win them back slowly.
     * On by default because it only bends down under throttling - a healthy host never triggers it -
     * and hammering a rate-limiting server is how a download gets its IP banned.
     */
    val httpAdaptiveConnectionsEnabled: Boolean = true,

    /**
     * Concurrent HLS segment fetches in the worker pool. Segments are small independent requests so they
     * parallelise well, but the pool stays modest to avoid tripping a host's per-IP connection limit.
     * Defaults to the pre-existing 4 so turning the knob on changes throughput only when asked.
     */
    val hlsMaxConcurrentSegments: Int = 4,

    /**
     * Opt-in: allow an external ffmpeg (located at runtime, never bundled) to remux HLS streams whose
     * container Blaze cannot assemble by pure-JVM byte-concatenation. **Off by default** - the default
     * path is pure-JVM and never needs ffmpeg; MPEG-TS and single-track fragmented-MP4 always concat.
     * When on, a needs-remux stream is only handed over if the ffmpeg binary is actually found, otherwise
     * the honest "unsupported" outcome still stands. Blaze never transcodes or bypasses protection here.
     */
    val allowFfmpegRemux: Boolean = false,

    /** Explicit path to the ffmpeg executable; null means search the OS PATH for `ffmpeg`. */
    val ffmpegPath: String? = null,
    val globalSpeedLimitEnabled: Boolean = false,
    val globalSpeedLimitKbps: Long = 10240, // default 10 MB/s

    /**
     * "Snail Mode": a quick, engine-wide crawl that overrides the normal speed limit so the
     * connection stays usable for other work while downloads keep trickling along.
     */
    val snailModeEnabled: Boolean = false,
    val snailSpeedKbps: Long = 64,
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
    /** Ignore the time limit and keep seeding until stopped manually. */
    val unlimitedSeeding: Boolean = false,
    /** Stop seeding once uploaded/downloaded reaches this share ratio; 0 disables the ratio cap. */
    val seedRatioLimit: Double = 0.0,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val logLevel: LogLevel = LogLevel.INFO,
    val fileLoggingEnabled: Boolean = true
)
