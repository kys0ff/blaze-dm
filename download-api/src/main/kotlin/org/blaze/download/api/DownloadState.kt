package org.blaze.download.api

/**
 * The explicit, client-safe lifecycle states of a download.
 *
 * This is the single source of truth for download state above the engine. It is a plain enum so it
 * serializes identically for every transport and language client (a browser extension, a Python
 * script, the CLI). The engine's own internal state hierarchy is mapped onto these names by the
 * application service; no client ever sees engine types. [COMPLETING] is reserved for a finalizing
 * phase the current engine folds into [COMPLETED], so it may never appear today but clients can
 * handle it without breaking.
 */
enum class DownloadState {
    QUEUED,
    STARTING,
    DOWNLOADING,
    PAUSING,
    PAUSED,
    RESUMING,
    VERIFYING,
    RESOLVING_METADATA,
    COMPLETING,
    COMPLETED,
    SEEDING,
    FAILED,
    CANCELLED,
    ;

    /** True while the transfer is consuming CPU/network and a progress event may follow. */
    val isActive: Boolean
        get() = this == STARTING || this == DOWNLOADING || this == RESUMING ||
            this == VERIFYING || this == RESOLVING_METADATA || this == PAUSING ||
            this == COMPLETING || this == SEEDING

    /** True once no further progress events will be emitted for the current run. */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}
