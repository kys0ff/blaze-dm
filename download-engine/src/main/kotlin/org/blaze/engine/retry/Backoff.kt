package org.blaze.engine.retry

/**
 * The engine's retry-timing schedule, shared by the HTTP chunk workers and the HLS segment fetcher so
 * both back off a struggling host the same way instead of re-implementing the arithmetic twice.
 *
 * The shape is **equal-jitter exponential**: the delay window doubles per attempt (base, then base·2,
 * base·4, … capped at [cap]), and the actual wait lands in the upper half of that window
 * (`half + rand(half)`). Full jitter (`rand(0..window)`) decorrelates a thundering herd slightly better
 * but can, with unlucky draws, collapse to an immediate retry — which defeats the point on a single
 * worker and makes the backoff look like it never landed. Equal-jitter keeps the decorrelation *and* a
 * hard floor per attempt.
 *
 * A real `Retry-After` from the server short-circuits the schedule entirely: the host knows its own
 * queue length better than our generic doubling does, so we honour it verbatim (already clamped
 * upstream by [org.blaze.engine.network.HttpNetworkClient.parseRetryAfter]).
 */
object Backoff {
    /** First retry window starts at 1 s; four attempts then span ~[0.5,1]/[1,2]/[2,4]/[4,8] s. */
    const val DEFAULT_BASE_MS = 1_000L

    /** Ceiling so a long retry budget cannot grow a delay into the absurd. */
    const val DEFAULT_CAP_MS = 30_000L

    /** Per-attempt jitter source; [java.util.Random] is thread-safe and cheap enough for this. */
    private val random = java.util.Random()

    /**
     * Delay before the [attempt]-th retry (1-based). Passes a server [retryAfterMillis] straight
     * through; otherwise computes an equal-jitter exponential step of [base] doubling each attempt,
     * capped at [cap].
     */
    fun equalJitterMillis(
        attempt: Int,
        retryAfterMillis: Long?,
        base: Long = DEFAULT_BASE_MS,
        cap: Long = DEFAULT_CAP_MS,
    ): Long {
        if (retryAfterMillis != null) return retryAfterMillis.coerceAtLeast(0L)
        val shift = (attempt - 1).coerceIn(0, 16)
        val exponential = (base shl shift).coerceAtMost(cap)
        val half = exponential / 2
        return if (half <= 0L) exponential else half + random.nextLong(half)
    }
}
