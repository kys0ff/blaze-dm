package org.blaze.engine.api

import kotlinx.serialization.Serializable

/**
 * Scheduling importance of a download.
 *
 * The order of the constants is the order of urgency, so [weight] and the queue comparator can be
 * derived from the declaration position. Two things depend on it: when a concurrency slot frees the
 * scheduler starts the highest-weight queued task first, and a transfer's default connection budget
 * is scaled by [connectionsFor] so a background download can't crowd out an urgent one.
 *
 * [NORMAL] is the default and behaves exactly like the pre-priority engine: same queue order (ties
 * fall back to creation time) and the full configured connection budget.
 */
@Serializable
enum class DownloadPriority(val weight: Int) {
    /** Only ever competes with other idle work: one connection, last in line. */
    IDLE(0),
    LOW(1),
    NORMAL(2),
    HIGH(3);

    /**
     * Scale [base] - the global per-download connection setting - into this priority's share.
     *
     * Bounded so a priority can never, on its own, drive a transfer below one connection or push it
     * past what the engine considers sane; the caller still clamps to the hard maximum.
     */
    fun connectionsFor(base: Int): Int = when (this) {
        IDLE -> 1
        LOW -> (base / 2).coerceAtLeast(1)
        NORMAL -> base.coerceAtLeast(1)
        HIGH -> (base * 2).coerceAtLeast(1)
    }

    companion object {
        val DEFAULT: DownloadPriority = NORMAL

        /** Tolerant parse for persisted/transported values: anything unknown falls back to [NORMAL]. */
        fun fromString(raw: String?): DownloadPriority =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NORMAL
    }
}
