package org.blaze.download.api

/**
 * The explicit, client-safe lifecycle states of a download.
 *
 * This is the single source of truth for download state above the engine. It is a plain enum so it
 * serializes identically for every transport and language client (a browser extension, a Python
 * script, the CLI). The engine's own internal state hierarchy is mapped onto these names by the
 * application service; no client ever sees engine types.
 *
 * [PROBING] and [MERGING] are real, observable engine phases (capability/playlist inspection, and
 * post-transfer assembly such as HLS concatenation or selective-archive decompression). [VERIFYING]
 * and [COMPLETING] are reserved: the engine performs no discrete verification pass today and folds
 * the finalizing rename into [COMPLETED], so neither may appear yet - but clients can handle them
 * without breaking, exactly as with any future value.
 */
enum class DownloadState {
    QUEUED,
    STARTING,
    PROBING,
    DOWNLOADING,
    MERGING,
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
        get() = this == STARTING || this == PROBING || this == DOWNLOADING || this == MERGING ||
            this == RESUMING || this == VERIFYING || this == RESOLVING_METADATA || this == PAUSING ||
            this == COMPLETING || this == SEEDING

    /** True once no further progress events will be emitted for the current run. */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}
