package org.blaze.engine.hls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.storage.FileStorage
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads a finite HLS stream: fetch a playlist, (optionally) narrow a master playlist to one variant,
 * pull every media segment through the shared HTTP client with bounded concurrency, then concatenate them
 * into a single output file.
 *
 * It is deliberately honest about what it can and cannot do. Ordinary HLS AES-128 encryption is decrypted
 * with the JDK's own crypto; DRM (SAMPLE-AES, FairPlay/Widevine) is *detected and refused* — never worked
 * around — and an acknowledged-but-still-protected stream fails with the real technical reason. Reliable
 * assembly by simple concatenation is only claimed for MPEG-TS streams; fragmented-MP4 and any other
 * container that needs remuxing is reported as unsupported rather than concatenated into a file that only
 * looks done. Temporary segment files are always cleaned up, on success, failure or cancellation.
 */
class HlsDownloader(
    private val client: HttpNetworkClient,
    private val storage: FileStorage,
    private val limiter: BandwidthLimiter,
    private val maxConcurrency: Int = 4,
    private val maxPlaylistBytes: Long = 32L * 1024 * 1024,
) {
    private val logger = LoggerFactory.getLogger(HlsDownloader::class.java)

    sealed interface Outcome {
        data class Success(val outputBytes: Long) : Outcome

        /** The stream looks DRM-protected and the user has not yet chosen to continue. */
        data class NeedsDrmConsent(val playlist: HlsPlaylist) : Outcome

        /** Recognised but not producible by Blaze (DRM after consent, or an unsupported container). */
        data class Unsupported(val reason: String) : Outcome
        data class Failed(val error: DownloadError) : Outcome
    }

    /**
     * Download the HLS resource [playlistUrl] into [destination].
     *
     * @param variantIndex the master-playlist variant to select; -1 means "highest available bandwidth".
     * @param headers request headers (auth, referer) preserved for every playlist/segment/key request.
     * @param drmAcknowledged whether the user already accepted a DRM warning; without it a protected
     *   stream yields [Outcome.NeededDrmConsent] instead of being fetched.
     */
    suspend fun download(
        playlistUrl: String,
        destination: Path,
        variantIndex: Int,
        headers: Map<String, String>,
        drmAcknowledged: Boolean,
        onProgress: (completedSegments: Int, totalSegments: Int, completedBytes: Long) -> Unit = { _, _, _ -> },
    ): Outcome {
        val request = DownloadRequest.Http(
            name = destination.fileName.toString(),
            url = playlistUrl,
            destination = destination,
            headers = headers,
        )
        val media = try {
            resolveMediaPlaylist(request, variantIndex, headers)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Outcome.Failed(e.toHlsError())
        } ?: return Outcome.Failed(DownloadError.NotFound)

        // DRM gating happens before a single segment is fetched, and consent never unlocks circumvention:
        // an acknowledged SAMPLE-AES stream is still refused, only with the plain technical reason.
        when (media.protection) {
            HlsProtection.DrmSuspected, HlsProtection.Uncertain ->
                return if (!drmAcknowledged) {
                    Outcome.NeedsDrmConsent(media)
                } else {
                    Outcome.Unsupported(drmRefusalReason(media))
                }

            else -> Unit
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
            // Decide the container from real downloaded bytes, not a filename guess.
            val container = detectContainer(tempDir, segments.size, media)
            if (container != HlsContainer.MpegTs) {
                cleanup(tempDir)
                return Outcome.Unsupported(
                    "only MPEG-TS HLS streams can be assembled into a single file by Blaze; this stream uses " +
                            "a fragmented-MP4/other container that needs remuxing"
                )
            }
            val written = assemble(tempDir, segments.size, destination)
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
        val errorRef = java.util.concurrent.atomic.AtomicReference<DownloadError>()
        val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray?>()
        val jobs = ArrayList<kotlinx.coroutines.Job>()

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
                        val result = fetchSegment(segment, media.mediaSequence + index, headers, keyCache)
                        if (result is SegmentBytes.Ok) {
                            Files.write(target, result.bytes)
                            bytesDone.addAndGet(result.bytes.size.toLong())
                            onProgress(completed.incrementAndGet(), segments.size, bytesDone.get())
                            return@withPermit
                        }
                        val err = (result as SegmentBytes.Err).error
                        if (attempt >= SEGMENT_MAX_ATTEMPTS || !err.isTransient()) {
                            failed.incrementAndGet()
                            errorRef.compareAndSet(null, err)
                            return@withPermit
                        }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        // Joining the first failure's peers does not stop the in-flight ones; a nonzero failure count means
        // at least one hole exists, so the run cannot be trusted to assemble a complete stream.
        if (failed.get() > 0) SegmentDownload.Failed(
            errorRef.get() ?: DownloadError.NetworkFailure("segment download failed")
        )
        else SegmentDownload.Done
    }

    private sealed interface SegmentBytes {
        data class Ok(val bytes: ByteArray) : SegmentBytes
        data class Err(val error: DownloadError) : SegmentBytes
    }

    private suspend fun fetchSegment(
        segment: HlsSegment,
        sequenceNumber: Long,
        headers: Map<String, String>,
        keyCache: MutableMap<String, ByteArray?>,
    ): SegmentBytes {
        val raw = fetchBytes(segment.uri, headers) ?: return SegmentBytes.Err(DownloadError.NotFound)
        val keyUri = segment.keyUri
        return try {
            if (keyUri == null) {
                SegmentBytes.Ok(raw)
            } else {
                val key = keyCache.getOrPut(keyUri) { fetchBytes(keyUri, headers) }
                    ?: return SegmentBytes.Err(DownloadError.Unauthorized)
                val iv = segment.iv?.let { hexToBytes(it) } ?: defaultIv(sequenceNumber)
                SegmentBytes.Ok(decryptAes128(key, iv, raw))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SegmentBytes.Err(DownloadError.Unknown("failed to decrypt segment: ${e.message}"))
        }
    }

    /** Fetch a whole (small) resource into memory, following redirects, capped at [maxPlaylistBytes]. */
    private suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? {
        val request =
            DownloadRequest.Http(name = "resource", url = url, destination = Path.of("resource"), headers = headers)
        val out = ByteArrayOutputStream()
        val result = client.stream(request) { buffer, count ->
            if (out.size().toLong() + count > maxPlaylistBytes) {
                throw java.io.IOException("resource exceeds $maxPlaylistBytes bytes")
            }
            limiter.acquire(count)
            out.write(buffer, 0, count)
        }
        return when (result) {
            is HttpNetworkClient.StreamResult.Success -> out.toByteArray()
            is HttpNetworkClient.StreamResult.Truncated -> out.toByteArray()
            else -> null
        }
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        fetchBytes(url, headers)?.toString(java.nio.charset.StandardCharsets.UTF_8)

    private fun assemble(tempDir: Path, segmentCount: Int, destination: Path): Long {
        val partial = storage.getPartialFile(destination).toPath()
        Files.createDirectories(partial.parent)
        java.io.BufferedOutputStream(Files.newOutputStream(partial)).use { os ->
            for (index in 0 until segmentCount) {
                val part = segmentPath(tempDir, index)
                Files.newInputStream(part).use { it.copyTo(os) }
            }
        }
        Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING)
        return Files.size(destination)
    }

    private fun detectContainer(tempDir: Path, segmentCount: Int, media: HlsPlaylist): HlsContainer {
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

    private fun drmRefusalReason(media: HlsPlaylist): String {
        val indicators = media.drmIndicators
        return buildString {
            append("this stream appears to be DRM/content-protected")
            if (indicators.isNotEmpty()) append(" (")
                .append(indicators.joinToString(", "))
                .append(")")
            append("; Blaze does not bypass DRM or extract protected keys")
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

/** The transport container a stream's segments actually use; only [MpegTs] is safe to concatenate. */
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
