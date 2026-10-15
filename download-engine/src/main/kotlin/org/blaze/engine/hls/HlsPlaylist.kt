package org.blaze.engine.hls

/**
 * Parsed representation of an HLS (`application/vnd.apple.mpegurl` / `application/x-mpegURL`) playlist.
 *
 * The parser is deliberately pure and offline: given the playlist *text* and the *base URL* it was
 * fetched from, it produces the segment/variant structure with every relative reference already
 * resolved against the correct base. Keeping parsing free of any I/O is what lets the whole state
 * machine — master vs. media, encryption, DRM indication, malformed input — be unit-tested against
 * fixed strings instead of a live-streaming host.
 */

/** Whether a playlist describes a set of variants (master) or a single playable sequence (media). */
enum class HlsPlaylistKind { Master, Media }

/** The `METHOD` of the active `#EXT-X-KEY`. Only [Aes128] is decryptable with the JDK's own crypto. */
enum class HlsKeyMethod { None, Aes128, SampleAes, Unknown }

/**
 * Blaze's honest read of how a stream is protected. These are *distinct* categories, exactly as the
 * task requires: ordinary HLS encryption is not DRM, a confirmed DRM signal is not a guess, and an
 * unrecognized method is neither. Only [OrdinaryEncryption] is actually handled by the downloader;
 * [DrmSuspected] and [Uncertain] surface a warning and let the user decide, and never trigger any
 * circumvention.
 */
enum class HlsProtection { None, OrdinaryEncryption, DrmSuspected, Uncertain }

/**
 * A byte window into a resource, from a resolved `#EXT-X-BYTERANGE` (`n[@o]`) or an `EXT-X-MAP`
 * `BYTERANGE` attribute. [start] and [length] are absolute: the parser resolves the offset-omitted
 * form (`n`, meaning "continue after the previous window") into an explicit [start] at parse time,
 * because fragments are fetched concurrently and the continuation cannot be reconstructed from fetch
 * order. Per RFC 8216 a window with no offset continues from the end of the preceding window; the very
 * first such window starts at byte 0.
 */
data class HlsByteRange(val start: Long, val length: Long) {
    /** Inclusive last byte index, for a `Range: bytes=start-end` request. */
    val endInclusive: Long get() = start + length - 1
}

/**
 * One `#EXTINF` segment of a media playlist, with its URL already resolved to absolute form.
 *
 * [keyUri] carries the AES-128 key location in force for *this* segment (HLS allows the key to rotate
 * mid-playlist via repeated `#EXT-X-KEY`), and [iv] the explicit initialization vector when the tag
 * supplies one; both are null for an unencrypted segment. [byteRange] is the `#EXT-X-BYTERANGE` window
 * when the segment is a slice of a larger file (fragmented-MP4 packs many media segments into one
 * `.m4s`); null means the whole resource at [uri] is this segment.
 */
data class HlsSegment(
    val uri: String,
    val durationSeconds: Double,
    val discontinuity: Boolean,
    val keyUri: String? = null,
    val iv: String? = null,
    val byteRange: HlsByteRange? = null,
)

/** One `#EXT-X-STREAM-INF` variant offered by a master playlist. */
data class HlsVariant(
    val index: Int,
    val uri: String,
    val bandwidth: Long?,
    val resolution: String?,
    val codecs: String?,
    val language: String?,
    val name: String?,
)

/**
 * The outcome of parsing a playlist. Exactly one of the shapes is populated, according to [kind];
 * inspecting a playlist is what lets Blaze tell a variant to pick from a sequence to fetch.
 *
 * [protection] is the classifier's assessment; [drmIndicators] name the concrete tags that drove it
 * so the UI can explain (rather than merely assert) why a stream looks protected.
 */
data class HlsPlaylist(
    val kind: HlsPlaylistKind,
    val segments: List<HlsSegment>,
    val variants: List<HlsVariant>,
    val targetDurationSeconds: Double?,
    val mediaSequence: Long,
    val endList: Boolean,
    val keyMethod: HlsKeyMethod,
    val keyUri: String?,
    val protection: HlsProtection,
    val drmIndicators: List<String>,
    /**
     * The `#EXT-X-MAP` initialization segment for a fragmented-MP4 media playlist: its URL and any byte
     * window within it. Null for an MPEG-TS stream (which needs no init segment). Its presence is what
     * makes a `.m4s`/CMAF stream concatenable - the init segment (ftyp+moov) precedes the fragments
     * (moof+mdat) to form one valid ISOBMFF file, with no remuxing.
     */
    val initMapUri: String? = null,
    val initMapByteRange: HlsByteRange? = null,
) {
    /** A VOD playlist declares `#EXT-X-ENDLIST`; without it the stream is (or was) live. */
    val isLive: Boolean get() = kind == HlsPlaylistKind.Media && !endList

    /** Media playlists are directly downloadable; master playlists must be narrowed to a variant. */
    val isPlayableMedia: Boolean get() = kind == HlsPlaylistKind.Media && segments.isNotEmpty()
}

/** Thrown when a response claimed to be a playlist is not one Blaze can understand. */
class HlsPlaylistException(message: String) : IllegalArgumentException(message)
