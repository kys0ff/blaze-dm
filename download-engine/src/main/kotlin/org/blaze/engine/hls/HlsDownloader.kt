package org.blaze.engine.hls

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.retry.Backoff
import org.blaze.engine.storage.FileStorage
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.milliseconds

/**
 * Downloads a finite HLS stream: fetch a playlist, (optionally) narrow a master playlist to one variant,
 * pull every media segment through the shared HTTP client with bounded concurrency, then concatenate them
 * into a single output file.
 *
 * It is deliberately honest about what it can and cannot do. Ordinary HLS AES-128 encryption is decrypted
 * with the JDK's own crypto. A *suspected* DRM stream is a warning, not a block: Blaze asks the user to
 * acknowledge before fetching anything, and an acknowledged stream still runs on the ordinary download path
 * — it is only ever stopped when the concrete evidence says so (segments encrypted with a scheme Blaze does
 * not decrypt, or a container that needs remuxing), never merely because the detector labeled it "DRM".
 * Blaze never works around protection or extracts DRM keys, and never writes a file it would present as a
 * processed video when the essence cannot actually be decrypted. Reliable assembly by simple concatenation
 * is claimed for MPEG-TS and for single-track fragmented-MP4 (whose `#EXT-X-MAP` init segment is prepended
 * to the fragments to form a valid ISOBMFF file with no remuxing); any other container that needs remuxing
 * is reported as unsupported rather than concatenated into a file that only looks done — unless an optional
 * [MediaRemuxer] (the opt-in ffmpeg path) is wired and its tool is present, in which case the already-fetched
 * segments are handed to it and only a successful remux yields a file. Temporary segment
 * files are always cleaned up, on success, failure or cancellation.
 */
class HlsDownloader(
    private val client: HttpNetworkClient,
    private val storage: FileStorage,
    private val limiter: BandwidthLimiter,
    private val maxConcurrency: Int = 4,
    private val maxPlaylistBytes: Long = 32L * 1024 * 1024,
    /**
     * Optional external remuxer for containers that cannot be assembled by byte-concatenation. Null by
     * default, so the pure-JVM path is unchanged and never requires an external tool; when one is wired
     * (the opt-in ffmpeg setting is on) a needs-remux stream is handed to it only if its binary is
     * actually present, otherwise the honest unsupported refusal stands.
     */
    private val remuxer: MediaRemuxer? = null,
) {
    private val logger = LoggerFactory.getLogger(HlsDownloader::class.java)

    sealed interface Outcome {
        data class Success(val outputBytes: Long) : Outcome

        /** The stream looks DRM-protected and the user has not yet chosen to continue. */
        data class NeedsDrmConsent(val playlist: HlsPlaylist) : Outcome

        /** Recognised but not producible on the ordinary path (an undecryptable scheme, or an unsupported container). */
        data class Unsupported(val reason: String) : Outcome
        data class Failed(val error: DownloadError) : Outcome
    }

    /**
     * The coarse lifecycle phase a run is in, reported through [download]'s `onPhase` so the caller can
     * label the pre-fetch playlist walk and the post-fetch assembly distinctly from segment transfer.
     * [Downloading] is implied by the byte/segment progress callback and is not signalled here.
     */
    enum class Phase { Probing, Merging }

    /**
     * Download the HLS resource [playlistUrl] into [destination].
     *
     * @param variantIndex the master-playlist variant to select; -1 means "highest available bandwidth".
     * @param headers request headers (auth, referer) preserved for every playlist/segment/key request.
     * @param drmAcknowledged whether the user already accepted a potential-DRM warning. Without it a
     *   protected-looking stream yields [Outcome.NeedsDrmConsent] and nothing is fetched; with it the
     *   ordinary download attempt proceeds — the acknowledgement unlocks the attempt, never a way around
     *   protection, and a stream is only refused afterwards on concrete evidence (an undecryptable key
     *   scheme or an unsupported container), not on the detector's label alone.
     */
    suspend fun download(
        playlistUrl: String,
        destination: Path,
        variantIndex: Int,
        headers: Map<String, String>,
        drmAcknowledged: Boolean,
        onPhase: (Phase) -> Unit = {},
        onProgress: (completedSegments: Int, totalSegments: Int, completedBytes: Long) -> Unit = { _, _, _ -> },
    ): Outcome {
        val request = DownloadRequest.Http(
            name = destination.fileName.toString(),
            url = playlistUrl,
            destination = destination,
            headers = headers,
        )
        // Resolving master -> variant -> media is genuine pre-transfer work; surface it as probing so the
        // list is not blank while the playlist walk runs, before a single segment byte is fetched.
        onPhase(Phase.Probing)
        val media = try {
            resolveMediaPlaylist(request, variantIndex, headers)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Outcome.Failed(e.toHlsError())
        } ?: return Outcome.Failed(DownloadError.NotFound)

        // DRM is a WARNING, not a block. An unacknowledged protected-looking stream asks for consent
        // before a single segment is fetched; acknowledging never unlocks circumvention. Crucially a
        // "DRM"/"uncertain" classification is NOT itself a reason to refuse: the attempt below runs the
        // ordinary download path, and whether the media is genuinely recoverable is decided by the
        // concrete key method and the real segment bytes, never by the detector's label alone.
        if (!drmAcknowledged &&
            (media.protection == HlsProtection.DrmSuspected || media.protection == HlsProtection.Uncertain)
        ) {
            return Outcome.NeedsDrmConsent(media)
        }

        if (media.isLive) {
            return Outcome.Unsupported("live/event HLS streams are not supported; only finite (VOD) playlists can be saved")
        }

        val segments = media.segments
        if (segments.isEmpty()) return Outcome.Failed(DownloadError.MetadataTimeout)

        val tempDir = destination.resolveSibling("${destination.fileName}.hls-parts")
        return try {
            storage.ensureDirectory(tempDir)
            val downloadResult = downloadSegments(request, media, segments, tempDir, headers, onProgress)
            if (downloadResult is SegmentDownload.Failed) {
                cleanup(tempDir)
                return Outcome.Failed(downloadResult.error)
            }
            // Every segment is now on disk; from here the work is putting them together (container sniff,
            // init-segment prepend, byte-concat or the opt-in remux), which is assembly, not transfer.
            onPhase(Phase.Merging)
            // Decide the container from real downloaded bytes, not a filename guess.
            val container = detectContainer(tempDir, segments.size, media)
            // Only a scheme Blaze genuinely cannot decrypt stops assembly — never the warning label.
            // SAMPLE-AES leaves the TS packet headers in the clear, so a sync-byte sniff cannot certify
            // the essence as playable, and it is refused on the method itself; an unrecognized method is
            // refused only when the real bytes also fail to look like a container Blaze can concatenate,
            // so a stream that a mere DRM advertisement mislabeled but which is actually clear still
            // downloads and succeeds. Either way Blaze decrypts nothing it has no key for.
            val cannotDecrypt = media.keyMethod == HlsKeyMethod.SampleAes ||
                    (media.keyMethod == HlsKeyMethod.Unknown && container != HlsContainer.MpegTs)
            if (cannotDecrypt) {
                cleanup(tempDir)
                return Outcome.Unsupported(undecryptableReason(media))
            }
            // MPEG-TS is concatenated as-is. A single-track fragmented-MP4 stream is too, once its
            // init segment (EXT-X-MAP) is prepended: init (ftyp+moov) followed by the fragments
            // (moof+mdat) is already a valid ISOBMFF file, so no remux is required. Any other container
            // genuinely needs a remux and is refused rather than faked into a broken file.
            val initMapUri = media.initMapUri
            val fmp4 = container == HlsContainer.Fmp4 && initMapUri != null
            if (container != HlsContainer.MpegTs && !fmp4) {
                // Byte-concatenation cannot make a valid file from this container. The pure-JVM path ends
                // here; remuxing is an *opt-in*, external step. Only when a remuxer was wired AND its tool
                // is genuinely present do we hand the already-fetched segments over. Absent or unavailable,
                // the honest refusal below is unchanged - Blaze never fakes a processed video and never
                // requires an external binary.
                val remuxer = this.remuxer
                if (remuxer != null && remuxer.isAvailable()) {
                    val parts = (0 until segments.size).map { segmentPath(tempDir, it) }
                    val remuxed = remuxer.remux(parts, destination)
                    cleanup(tempDir)
                    return when (remuxed) {
                        is RemuxOutcome.Success -> Outcome.Success(Files.size(destination))
                        is RemuxOutcome.Failure -> Outcome.Unsupported(
                            "this stream needs remuxing and the external remuxer produced no playable " +
                                    "file: ${remuxed.reason}"
                        )
                    }
                }
                cleanup(tempDir)
                return Outcome.Unsupported(
                    "only MPEG-TS and single-track fragmented-MP4 HLS streams can be assembled into a " +
                            "single file by Blaze; this stream uses a container that needs remuxing"
                )
            }
            // The init segment is fetched once and written ahead of the fragments. Its bytes define the
            // container, so a failure here is a hard failure, never a silent skip that drops the moov box.
            val initBytes: ByteArray? = if (fmp4) {
                val range = media.initMapByteRange
                val init = fetchResource(initMapUri, headers, range?.start ?: 0L, range?.endInclusive)
                if (init is Resource.Ok) {
                    init.bytes
                } else {
                    cleanup(tempDir)
                    val error = when (init) {
                        is Resource.Fatal -> init.error
                        is Resource.Retryable -> init.error
                    }
                    return Outcome.Failed(error)
                }
            } else null
            val written = assemble(tempDir, segments.size, destination, initBytes)
            cleanup(tempDir)
            Outcome.Success(written)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            cleanup(tempDir)
            logger.warn("HLS download to {} failed", destination, e)
            Outcome.Failed(e.toHlsError())
        }
    }

    /** Follow master → variant → media, at most a couple of hops, returning the media playlist to fetch. */
    private suspend fun resolveMediaPlaylist(
        request: DownloadRequest.Http,
        variantIndex: Int,
        headers: Map<String, String>,
    ): HlsPlaylist? {
        var url = request.url
        var hops = 0
        var last: HlsPlaylist? = null
        while (hops++ < MAX_PLAYLIST_HOPS) {
            val text = fetchText(url, headers) ?: return last?.let { throw HlsPlaylistException("playlist vanished") }
            val parsed = HlsPlaylistParser.parse(text, url)
            last = parsed
            if (parsed.kind == HlsPlaylistKind.Media) return parsed
            val variant = chooseVariant(parsed.variants, variantIndex) ?: return null
            url = variant.uri
        }
        return last
    }

    private fun chooseVariant(variants: List<HlsVariant>, index: Int): HlsVariant? {
        if (variants.isEmpty()) return null
        if (index in variants.indices) return variants[index]
        // Default to the highest advertised bandwidth so a "just download it" request yields the best
        // quality the stream offers; an unknown-bandwidth variant sorts last and is only picked if so.
        return variants.maxByOrNull { it.bandwidth ?: -1L }
    }

    /**
     * Inspect an HLS resource without downloading any media segment: resolve master→variant→media, expose
     * the selectable variants, and hand back the selected variant's parsed media playlist so the caller can
     * classify its protection and decide whether a DRM warning is warranted *before* committing a download.
     */
    suspend fun inspect(
        playlistUrl: String,
        variantIndex: Int,
        headers: Map<String, String>,
    ): Inspection {
        var url = playlistUrl
        var hops = 0
        var master: HlsPlaylist? = null
        var media: HlsPlaylist? = null
        var selected = variantIndex
        while (hops++ < MAX_PLAYLIST_HOPS) {
            val text = fetchText(url, headers) ?: throw HlsPlaylistException("playlist not found")
            val parsed = HlsPlaylistParser.parse(text, url)
            if (parsed.kind == HlsPlaylistKind.Media) {
                media = parsed
                break
            }
            master = parsed
            val variant = chooseVariant(parsed.variants, selected)
                ?: throw HlsPlaylistException("master playlist lists no variants")
            selected = variant.index
            url = variant.uri
        }
        val resolvedMedia = media ?: throw HlsPlaylistException("could not resolve a media playlist")
        val variants = master?.variants ?: listOf(
            HlsVariant(
                index = 0,
                uri = playlistUrl,
                bandwidth = null,
                resolution = null,
                codecs = null,
                language = null,
                name = null
            )
        )
        return Inspection(playlist = resolvedMedia, variants = variants, selectedVariantIndex = selected)
    }

    data class Inspection(
        val playlist: HlsPlaylist,
        val variants: List<HlsVariant>,
        val selectedVariantIndex: Int,
    )

    private sealed interface SegmentDownload {
        data object Done : SegmentDownload
        data class Failed(val error: DownloadError) : SegmentDownload
    }

    /**
     * Fetch every segment into `tempDir/<index>.part` with bounded concurrency. A transient per-segment
     * failure is retried a few times inside one pass; a segment that never succeeds fails the run, so a
     * hole can never be silently baked into the assembled file.
     */
    private suspend fun downloadSegments(
        request: DownloadRequest.Http,
        media: HlsPlaylist,
        segments: List<HlsSegment>,
        tempDir: Path,
        headers: Map<String, String>,
        onProgress: (completedSegments: Int, totalSegments: Int, completedBytes: Long) -> Unit,
    ): SegmentDownload = coroutineScope {
        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        val failed = AtomicLong(0)
        val completed = AtomicInteger(0)
        // Cumulative decrypted bytes written so far, so progress can be reported in bytes (which drives a
        // real speed/size) rather than as a bare segment counter the UI would render as "1b, 2b, 3b".
        val bytesDone = AtomicLong(0)
        val errorRef = AtomicReference<DownloadError>()
        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val jobs = ArrayList<Job>()

        segments.forEachIndexed { index, segment ->
            val target = segmentPath(tempDir, index)
            // Already-on-disk segments with the right size are kept as-is: this is the resume path after
            // a retry or restart, gated by the container check downstream, so a re-run does not re-fetch.
            val existing = if (Files.exists(target)) Files.size(target) else 0L
            if (existing > 0) {
                bytesDone.addAndGet(existing)
                onProgress(completed.incrementAndGet(), segments.size, bytesDone.get())
                return@forEachIndexed
            }
            jobs += launch {
                if (failed.get() > 0) return@launch
                semaphore.withPermit {
                    if (failed.get() > 0) return@withPermit
                    var attempt = 0
                    while (true) {
                        attempt++
                        when (val fetched = fetchSegment(segment, media.mediaSequence + index, headers, keyCache)) {
                            is Resource.Ok -> {
                                Files.write(target, fetched.bytes)
                                bytesDone.addAndGet(fetched.bytes.size.toLong())
                                onProgress(completed.incrementAndGet(), segments.size, bytesDone.get())
                                return@withPermit
                            }

                            // A permanent refusal (404/401/disk) fails the run at once - no point re-asking.
                            is Resource.Fatal -> {
                                failed.incrementAndGet()
                                errorRef.compareAndSet(null, fetched.error)
                                return@withPermit
                            }

                            // A transient answer - a 5xx/429, a reset, or a body that stopped short of its
                            // advertised length - is retried on the shared equal-jitter schedule, honoring a
                            // server `Retry-After` when the host sent one. A truncated segment is NEVER written
                            // as if it were whole: baking a short segment into the file is exactly the corrupt
                            // output the correctness rule forbids.
                            is Resource.Retryable -> {
                                if (attempt >= SEGMENT_MAX_ATTEMPTS) {
                                    failed.incrementAndGet()
                                    errorRef.compareAndSet(null, fetched.error)
                                    return@withPermit
                                }
                                delay(Backoff.equalJitterMillis(attempt, fetched.retryAfterMillis).milliseconds)
                            }
                        }
                    }
                }
            }
        }
        jobs.joinAll()
        // Joining the first failure's peers does not stop the in-flight ones; a nonzero failure count means
        // at least one hole exists, so the run cannot be trusted to assemble a complete stream.
        if (failed.get() > 0) SegmentDownload.Failed(
            errorRef.get() ?: DownloadError.NetworkFailure("segment download failed")
        )
        else SegmentDownload.Done
    }

    /**
     * Outcome of pulling one whole resource (a segment, a key, or a playlist) into memory. The failure
     * shapes are deliberately distinct so the caller can decide *how* to react: a truncated or
     * transiently-refused body is worth another attempt, while a 404/401/disk-full will never succeed and
     * must not be retried or - worse - written as if the bytes were complete.
     */
    private sealed interface Resource {
        val bytesOrNull: ByteArray? get() = (this as? Ok)?.bytes

        data class Ok(val bytes: ByteArray) : Resource {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Ok

                return bytes.contentEquals(other.bytes)
            }

            override fun hashCode(): Int {
                return bytes.contentHashCode()
            }
        }

        /** Retryable: a 5xx/429/timeout/reset, a range the server ignored, or a body that stopped short. */
        data class Retryable(
            val error: DownloadError,
            val retryAfterMillis: Long? = null,
            val throttled: Boolean = false,
        ) : Resource

        /** Permanent: re-asking cannot help, so the run fails on this immediately. */
        data class Fatal(val error: DownloadError) : Resource
    }

    /**
     * Fetch a segment into memory and decrypt it if the playlist put an AES-128 key in force for it. Any
     * non-`Ok` from the raw fetch (or from the key fetch) is propagated verbatim so the retry loop above
     * sees the real reason - including a `Retry-After` - rather than a flattened "not found".
     */
    private suspend fun fetchSegment(
        segment: HlsSegment,
        sequenceNumber: Long,
        headers: Map<String, String>,
        keyCache: MutableMap<String, ByteArray>,
    ): Resource {
        val range = segment.byteRange
        val raw = if (range != null) {
            // A fragmented-MP4 slice: pull exactly this byte window, then decrypt if a key is in force.
            fetchResource(segment.uri, headers, range.start, range.endInclusive)
        } else {
            fetchResource(segment.uri, headers)
        }
        if (raw !is Resource.Ok) return raw
        val keyUri = segment.keyUri ?: return raw
        val key = keyCache[keyUri] ?: run {
            val fetched = fetchResource(keyUri, headers)
            if (fetched !is Resource.Ok) return fetched
            fetched.bytes.also { keyCache[keyUri] = it }
        }
        return try {
            val iv = segment.iv?.let { hexToBytes(it) } ?: defaultIv(sequenceNumber)
            Resource.Ok(decryptAes128(key, iv, raw.bytes))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A decryption failure is deterministic for these bytes: retrying the same ciphertext will not
            // help, so it is fatal (and it never writes a half-decrypted segment into the assembly).
            Resource.Fatal(DownloadError.Unknown("failed to decrypt segment: ${e.message}"))
        }
    }

    /**
     * Fetch a whole (small) resource into memory, following redirects, capped at [maxPlaylistBytes], and
     * classify the HTTP outcome so the caller knows whether it is worth retrying. Truncation - the body
     * ending before its advertised length - is surfaced as retryable, never silently accepted as a short
     * success the way the old whole-or-null `fetchBytes` did.
     */
    private suspend fun fetchResource(
        url: String,
        headers: Map<String, String>,
        start: Long = 0L,
        end: Long? = null,
    ): Resource {
        val request =
            DownloadRequest.Http(name = "resource", url = url, destination = Path.of("resource"), headers = headers)
        val out = ByteArrayOutputStream()
        val result = client.stream(request, start = start, end = end) { buffer, count ->
            if (out.size().toLong() + count > maxPlaylistBytes) {
                throw java.io.IOException("resource exceeds $maxPlaylistBytes bytes")
            }
            limiter.acquire(count)
            out.write(buffer, 0, count)
        }
        return when (result) {
            is HttpNetworkClient.StreamResult.Success -> Resource.Ok(out.toByteArray())
            is HttpNetworkClient.StreamResult.Truncated -> Resource.Retryable(
                DownloadError.NetworkFailure(
                    "body truncated after ${result.received} of ${result.expected} bytes",
                ),
            )

            is HttpNetworkClient.StreamResult.RangeIgnored ->
                Resource.Retryable(DownloadError.NetworkFailure("server ignored the requested range"))

            is HttpNetworkClient.StreamResult.Failed ->
                if (result.error.isTransient())
                    Resource.Retryable(result.error, result.retryAfterMillis, result.throttled)
                else
                    Resource.Fatal(result.error)

            // `stream()` follows redirects internally and never hands a `Redirected` back to us; the
            // branch is here to keep the `when` exhaustive against the sealed result.
            is HttpNetworkClient.StreamResult.Redirected ->
                Resource.Fatal(DownloadError.Unknown("redirect was not followed"))
        }
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        fetchResource(url, headers).bytesOrNull?.toString(java.nio.charset.StandardCharsets.UTF_8)

    private fun assemble(
        tempDir: Path,
        segmentCount: Int,
        destination: Path,
        initBytes: ByteArray? = null,
    ): Long {
        val partial = storage.getPartialFile(destination).toPath()
        Files.createDirectories(partial.parent)
        java.io.BufferedOutputStream(Files.newOutputStream(partial)).use { os ->
            if (initBytes != null) os.write(initBytes)
            for (index in 0 until segmentCount) {
                val part = segmentPath(tempDir, index)
                Files.newInputStream(part).use { it.copyTo(os) }
            }
        }
        Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING)
        return Files.size(destination)
    }

    private fun detectContainer(tempDir: Path, segmentCount: Int, media: HlsPlaylist): HlsContainer {
        // An EXT-X-MAP init segment declares a fragmented-MP4 stream outright; a fMP4 fragment starts at a
        // box boundary (moof/mdat), not "ftyp", so sniffing the first fragment cannot classify it - the
        // map tag is the authoritative signal and must win before any byte sniff.
        if (media.initMapUri != null) return HlsContainer.Fmp4
        // Prefer a sniff of the first real segment bytes; fall back to the URI extension.
        val head = runCatching {
            val first = segmentPath(tempDir, 0)
            val bytes = ByteArray(minOf(8, Files.size(first).toInt()))
            Files.newInputStream(first).use { it.read(bytes) }
            bytes
        }.getOrNull() ?: ByteArray(0)

        if (head.isNotEmpty() && head[0] == 0x47.toByte()) return HlsContainer.MpegTs
        if (head.size >= 8 && String(head, 4, 4) == "ftyp") return HlsContainer.Fmp4
        val sampleUri =
            media.segments.firstOrNull()?.uri?.substringBefore('?')?.lowercase() ?: return HlsContainer.Other
        return when {
            sampleUri.endsWith(".ts") || sampleUri.endsWith(".mpg") || sampleUri.endsWith(".mpeg") -> HlsContainer.MpegTs
            sampleUri.endsWith(".m4s") || sampleUri.endsWith(".cmfv") || sampleUri.endsWith(".mp4") -> HlsContainer.Fmp4
            else -> HlsContainer.Other
        }
    }

    /**
     * The concrete reason an acknowledged stream still cannot be turned into a playable file: its segments
     * are encrypted with a scheme Blaze does not decrypt. This names the actual technical limitation (the
     * key method, plus any protection tags that revealed it) rather than refusing on the detector's warning
     * label, and states plainly that Blaze neither bypasses protection nor extracts protected keys. The
     * accessible segment bytes were still retrieved; only a decrypted, playable output could not be made.
     */
    private fun undecryptableReason(media: HlsPlaylist): String {
        val scheme = when (media.keyMethod) {
            HlsKeyMethod.SampleAes -> "SAMPLE-AES"
            HlsKeyMethod.Unknown -> "an unrecognized key method"
            else -> "an unsupported key method"
        }
        val indicators = media.drmIndicators
        return buildString {
            append("media segments are encrypted with ")
                .append(scheme)
                .append(" that Blaze does not decrypt, so no playable file can be produced")
            if (indicators.isNotEmpty()) {
                append(" (protection detected from: ")
                    .append(indicators.joinToString(", "))
                    .append(")")
            }
            append("; the accessible segment bytes were retrieved, but Blaze does not bypass DRM or extract protected keys")
        }
    }

    private fun cleanup(tempDir: Path) {
        runCatching { storage.deleteRecursively(tempDir) }
    }

    private fun segmentPath(tempDir: Path, index: Int): Path = tempDir.resolve("$index.part")

    companion object {
        private const val MAX_PLAYLIST_HOPS = 4
        private const val SEGMENT_MAX_ATTEMPTS = 3
    }
}

/**
 * The transport container a stream's segments actually use. [MpegTs] and [Fmp4] (a single-track stream with
 * an `#EXT-X-MAP` init segment) are both safe to concatenate; [Other] needs a remux and is refused.
 */
enum class HlsContainer { MpegTs, Fmp4, Other }

// ---------------------------------------------------------------- HLS crypto helpers (JDK-only, shared code safe)

internal fun decryptAes128(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(data)
}

/** The default HLS IV is the media-sequence-number-derived segment index as a big-endian 128-bit value. */
internal fun defaultIv(segmentIndex: Long): ByteArray {
    val iv = ByteArray(16)
    for (i in 0 until 8) iv[15 - i] = (segmentIndex ushr (8 * i)).toByte()
    return iv
}

internal fun hexToBytes(hex: String): ByteArray {
    val clean = hex.removePrefix("0x").removePrefix("0X")
    val padded = if (clean.length % 2 == 0) clean else "0$clean"
    return ByteArray(padded.length / 2) { i ->
        ((Character.digit(padded[i * 2], 16) shl 4) + Character.digit(padded[i * 2 + 1], 16)).toByte()
    }
}

internal fun Throwable.toHlsError(): DownloadError = when (this) {
    is HlsPlaylistException -> DownloadError.Unknown(message ?: "invalid HLS playlist")
    else -> DownloadError.NetworkFailure(message ?: javaClass.simpleName)
}

private fun DownloadError.isTransient(): Boolean = when (this) {
    is DownloadError.NetworkFailure, is DownloadError.Timeout, DownloadError.NetworkUnavailable -> true
    else -> false
}
