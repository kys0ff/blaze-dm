package org.blaze.engine.hls

import java.nio.file.Path

/**
 * An *optional* post-download remuxer for HLS streams whose container cannot be turned into a playable
 * file by simple byte-concatenation.
 *
 * The engine's default path is pure-JVM and never needs this: MPEG-TS segments and single-track
 * fragmented-MP4 (init + fragments) are concatenated directly into a valid file. Only a container that
 * genuinely requires remuxing is handed to a [MediaRemuxer], and only when one has been explicitly wired
 * (the opt-in ffmpeg path). Keeping the downloader dependent on this interface rather than on a concrete
 * external process is what lets the whole feature stay off-by-default, unit-testable with a fake, and free
 * of any hard dependency on an installed binary.
 *
 * Contract for implementations:
 *  - [isAvailable] answers whether the backing tool is actually present and runnable *now*; a false makes
 *    the caller fall back to the honest "unsupported" outcome rather than pretending to remux.
 *  - [remux] must be atomic: it writes a finished, playable file to [destination] only on success and
 *    leaves no partial/corrupt [destination] behind on failure. It is safe to suspend and to call from a
 *    coroutine.
 */
interface MediaRemuxer {
    /** Whether the underlying remux tool is installed and usable right now. */
    fun isAvailable(): Boolean

    /**
     * Remux the ordered segment [parts] (already-downloaded, decrypted media segments) into a single
     * playable [destination]. Never mutates [parts]; a null/failed outcome means the caller keeps its
     * existing honest refusal and must not present a processed video.
     */
    suspend fun remux(parts: List<Path>, destination: Path): RemuxOutcome
}

/** Result of a [MediaRemuxer.remux] call. [Failure.reason] is user-facing and must name the real cause. */
sealed interface RemuxOutcome {
    data object Success : RemuxOutcome
    data class Failure(val reason: String) : RemuxOutcome
}
