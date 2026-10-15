package org.blaze.engine.api

sealed interface DownloadState {
    data object Queued : DownloadState
    data object Starting : DownloadState

    /**
     * Deciding *what* to fetch and *how*, before any media byte moves: the HTTP capability probe
     * (a HEAD/ranged GET), the HLS playlist walk (master to variant to media), or a selective
     * archive's central-directory inspect. Torrents use [ResolvingMetadata] for the same idea, so
     * they never emit [Probing].
     */
    data object Probing : DownloadState
    data object Downloading : DownloadState

    /**
     * A post-transfer assembly the engine genuinely performs once the bytes are down: HLS segment
     * concatenation/remux and selective-archive decompression. It is distinct from [Downloading]
     * (no bytes are being fetched from the source during this phase).
     */
    data object Merging : DownloadState
    data object Pausing : DownloadState
    data object Paused : DownloadState
    data object Resuming : DownloadState

    /**
     * Reserved for a discrete integrity-check pass. No protocol runs a separate verification phase
     * today - HTTP never checksums, torrent piece hashing happens continuously inside the `bt`
     * engine on each write, HLS/archive assemble without a verify step - so the engine never emits
     * this; it is kept only to mirror the public enum and to give a future opt-in checksum feature
     * a home. Emitting it without such an operation would fabricate a phase, which is not done.
     */
    data object Verifying : DownloadState

    /**
     * Reserved finalizing phase that is folded into [Completed]: the partial -> destination rename
     * is effectively instantaneous, so there is no honest window to observe it in. Never emitted; it
     * exists so the engine model mirrors the public enum's [Completing] without a silent gap.
     */
    data object Completing : DownloadState
    data object Completed : DownloadState
    data object Failed : DownloadState
    data object Cancelled : DownloadState
    
    // Torrent specific
    data object ResolvingMetadata : DownloadState
    data object Seeding : DownloadState

    val isActive: Boolean
        get() = when (this) {
            Downloading, Starting, Resuming, Verifying, ResolvingMetadata, Seeding, Pausing, Probing, Merging -> true
            else -> false
        }

    companion object {
        fun fromString(raw: String): DownloadState = when (raw.uppercase()) {
            "COMPLETED" -> Completed
            "PAUSED" -> Paused
            "FAILED" -> Failed
            "CANCELLED" -> Cancelled
            "SEEDING" -> Seeding
            "DOWNLOADING" -> Downloading
            "STARTING" -> Starting
            "RESUMING" -> Resuming
            "VERIFYING" -> Verifying
            "PROBING" -> Probing
            "MERGING" -> Merging
            "COMPLETING" -> Completing
            "RESOLVINGMETADATA" -> ResolvingMetadata
            else -> Queued
        }

        fun toString(state: DownloadState): String =
            state::class.simpleName?.uppercase() ?: "QUEUED"
    }
}
