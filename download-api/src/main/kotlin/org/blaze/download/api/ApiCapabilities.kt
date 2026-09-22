package org.blaze.download.api

import kotlinx.serialization.Serializable

/** How finely the running engine can throttle transfer speed. */
@Serializable
enum class BandwidthControlSupport { NONE, GLOBAL_ONLY, PER_DOWNLOAD }

/** Whether downloads honor an explicit priority or are served strictly first-in-first-out. */
@Serializable
enum class PrioritySupport { NONE, FIFO_ONLY, EXPLICIT }

/** How transport authenticates callers. */
@Serializable
enum class AuthMode { NONE, BEARER_TOKEN, PEER_CREDENTIALS }

/**
 * Self-description of what this downloader build can actually do.
 *
 * Clients read it instead of hardcoding assumptions ("does this engine support pause? torrents?"), so a
 * future build that gains or drops a capability is discoverable without a client update. The service maps
 * this from the real engine behind it - it is not a static wish-list.
 */
@Serializable
data class ApiCapabilities(
    val apiVersion: Int,
    val supportsHttp: Boolean,
    val supportsHttps: Boolean,
    val supportsTorrent: Boolean,
    val supportsMagnet: Boolean,
    val supportsPortableDownloads: Boolean,
    val supportsPauseResume: Boolean,
    val supportsCancel: Boolean,
    val supportsRetry: Boolean,
    val supportsScheduling: Boolean,
    val supportsEventStream: Boolean,
    val bandwidthControl: BandwidthControlSupport,
    val prioritySupport: PrioritySupport,
    val authentication: AuthMode,
    val requiresLocalhost: Boolean,
)
