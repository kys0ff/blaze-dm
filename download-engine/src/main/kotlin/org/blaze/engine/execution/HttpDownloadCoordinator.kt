package org.blaze.engine.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.network.toDownloadError
import org.blaze.engine.persistence.HttpResumeState
import org.blaze.engine.retry.isRetryable
import org.blaze.engine.metrics.EngineMetrics
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.storage.FileStorage
import org.blaze.engine.storage.PositionedWriter
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.BitSet
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the whole HTTP data path: capability probe, single-stream vs segmented decision,
 * connection workers, disk writes, resume bookkeeping and progress accounting.
 *
 * Design notes:
 * - Segmentation only happens once the server has *proven* ranged reads and the file is big
 *   enough for the extra requests to pay off; everything else keeps the single-stream path.
 * - Work is handed out in chunks rather than as N equal halves, so a connection that turns out
 *   to be slow can never hold the download hostage: the other workers take the remaining chunks,
 *   and a fully stalled worker gets dropped by the watchdog.
 * - Every chunk is written at a fixed absolute offset, which makes retries and resumes
 *   idempotent - re-downloading a chunk overwrites exactly the same bytes.
 */
class HttpDownloadCoordinator(
    private val client: HttpNetworkClient,
    private val storage: FileStorage,
    private val limiter: BandwidthLimiter,
    private val settings: DownloadSettings,
    private val metrics: EngineMetrics = EngineMetrics()
) {
    private val logger = LoggerFactory.getLogger(HttpDownloadCoordinator::class.java)

    data class Progress(val downloadedBytes: Long, val totalBytes: Long?)

    sealed interface Outcome {
        data class Success(val downloadedBytes: Long, val totalBytes: Long?) : Outcome
        data class Failure(val error: DownloadError) : Outcome
    }

    /**
     * Fills [partial] with the full body of [request]. The caller owns the final
     * partial -> destination rename, exactly like before acceleration existed.
     */
    suspend fun download(
        request: DownloadRequest.Http,
        destination: Path,
        partial: Path,
        connections: Int,
        onProgress: suspend (Progress) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        val resumeFrom = sizeOf(partial)

        if (settings.httpAccelerationEnabled && connections > 1) {
            val probe = client.probe(request)
            if (probe.status == RANGE_NOT_SATISFIABLE && resumeFrom > 0) {
                // The bytes we already have go beyond the end of the file: it is complete.
                return@withContext Outcome.Success(resumeFrom, resumeFrom)
            }
            if (probe.acceptsRanges && probe.totalBytes >= settings.httpMinParallelSizeBytes) {
                segmented(request, destination, partial, probe, connections, onProgress)
            } else {
                singleStream(request, destination, partial, resumeFrom, probe.totalBytes, onProgress)
            }
        } else {
            singleStream(request, destination, partial, resumeFrom, totalHint = -1L, onProgress)
        }
    }

    // ---------------------------------------------------------------- single stream

    private sealed interface StreamOutcome {
        data object RangeIgnored : StreamOutcome
        data class Done(val downloadedBytes: Long, val totalBytes: Long?) : StreamOutcome
        data class Failed(val error: DownloadError) : StreamOutcome
    }

    private suspend fun singleStream(
        request: DownloadRequest.Http,
        destination: Path,
        partial: Path,
        resumeFrom: Long,
        totalHint: Long,
        onProgress: suspend (Progress) -> Unit
    ): Outcome {
        var offset = resumeFrom
        var restarted = false

        while (true) {
            val result = streamOnce(request, partial, offset, totalHint, onProgress)
            if (result is StreamOutcome.RangeIgnored && !restarted) {
                // What is on disk was written against a range the server does not honour; keeping
                // it would shift every byte, so start over instead of producing a corrupt file.
                logger.warn("Server ignored the resume range for {}; restarting from byte 0", destination.fileName)
                runCatching { storage.delete(partial) }
                runCatching { storage.delete(storage.getResumeStateFile(destination).toPath()) }
                offset = 0L
                restarted = true
                continue
            }
            return when (result) {
                is StreamOutcome.Done -> Outcome.Success(result.downloadedBytes, result.totalBytes)
                is StreamOutcome.Failed -> Outcome.Failure(result.error)
                StreamOutcome.RangeIgnored -> Outcome.Failure(DownloadError.RangeUnsupported)
            }
        }
    }

    private suspend fun streamOnce(
        request: DownloadRequest.Http,
        partial: Path,
        offset: Long,
        totalHint: Long,
        onProgress: suspend (Progress) -> Unit
    ): StreamOutcome {
        var total = totalHint.takeIf { it > 0 }
        var written = offset
        var lastReport = 0L

        val session = try {
            storage.openForWrite(partial).also {
                if (offset == 0L) it.setLength(0)
                it.seek(offset)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return StreamOutcome.Failed(e.toFileSystemError())
        }

        try {
            val result = client.stream(
                request = request,
                start = offset,
                end = null,
                onHead = { head ->
                    val known = when {
                        head.totalBytes > 0 -> head.totalBytes
                        head.bodyLength > 0 -> head.bodyLength + offset
                        else -> null
                    }
                    if (known != null) total = known
                },
                sink = { buffer, length ->
                    limiter.acquire(length)
                    session.write(buffer, 0, length)
                    written += length
                    metrics.recordBytes(length.toLong())
                    val now = System.nanoTime()
                    if (now - lastReport >= PROGRESS_INTERVAL_NS) {
                        lastReport = now
                        onProgress(Progress(written, total))
                    }
                }
            )

            onProgress(Progress(written, total))
            return when (result) {
                is HttpNetworkClient.StreamResult.Success -> StreamOutcome.Done(written, total)
                HttpNetworkClient.StreamResult.RangeIgnored -> StreamOutcome.RangeIgnored
                is HttpNetworkClient.StreamResult.Truncated -> StreamOutcome.Failed(
                    DownloadError.NetworkFailure(
                        "Server closed the connection early: ${result.received + offset} of ${result.expected + offset} bytes"
                    )
                )

                is HttpNetworkClient.StreamResult.Failed -> StreamOutcome.Failed(result.error)
                is HttpNetworkClient.StreamResult.Redirected -> StreamOutcome.Failed(
                    DownloadError.NetworkFailure("Unresolved redirect")
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return StreamOutcome.Failed(e.toFileSystemError())
        } finally {
            runCatching { session.close() }
        }
    }

    // ---------------------------------------------------------------- segmented

    private suspend fun segmented(
        request: DownloadRequest.Http,
        destination: Path,
        partial: Path,
        probe: HttpNetworkClient.Probe,
        connections: Int,
        onProgress: suspend (Progress) -> Unit
    ): Outcome {
        val total = probe.totalBytes
        val statePath = storage.getResumeStateFile(destination).toPath()
        var plan = HttpTransferPlanner.plan(total, connections, settings.httpChunkSizeMb * 1024L * 1024L)
        if (plan.chunkCount < 2) return singleStream(request, destination, partial, sizeOf(partial), total, onProgress)

        val completed = CompletedChunks(plan.chunkCount)
        val persisted = readResumeState(statePath)
        when {
            persisted == null -> {
                // No sidecar: a contiguous prefix (e.g. from an earlier single-stream attempt or a
                // crash) is still safe to reuse chunk-by-chunk.
                val contiguous = sizeOf(partial)
                if (contiguous in 1 until total) {
                    var chunks = 0
                    while (chunks < plan.chunkCount && plan.endOf(chunks, total) < contiguous) {
                        completed.markDone(chunks)
                        chunks++
                    }
                    logger.info(
                        "Reusing {} completed chunks ({} bytes) of {} from the existing partial file",
                        chunks, contiguous, destination.fileName
                    )
                }
            }

            persisted.totalBytes == total && persisted.validator.orEmpty() == probe.validator.orEmpty() -> {
                // Keep the original chunk layout, otherwise "chunk 7 done" would mean something else.
                plan = HttpTransferPlanner.Plan(persisted.chunkSize, chunkCountFor(total, persisted.chunkSize))
                if (plan.chunkCount < 2) return singleStream(request, destination, partial, sizeOf(partial), total, onProgress)
                val restored = persisted.toBitSet()
                for (index in 0 until minOf(plan.chunkCount, restored.size())) {
                    if (restored.get(index)) completed.markDone(index)
                }
            }

            else -> {
                // The server is serving a different file than the one the state describes.
                logger.warn("Stored resume state for {} no longer matches the server; restarting the transfer", destination.fileName)
                runCatching { storage.delete(statePath) }
                runCatching { storage.delete(partial) }
            }
        }

        var base = 0L
        for (index in 0 until plan.chunkCount) {
            if (completed.isDone(index)) base += plan.sizeOf(index, total)
        }

        destination.parent?.let { runCatching { storage.ensureDirectory(it) } }
        // Record the chunk layout *before* the file is grown to its final size. A sparse partial is
        // indistinguishable from a fully downloaded one by size alone, so if the process died in
        // the window before the first periodic write, the next run would have no sidecar and would
        // trust its "contiguous prefix" heuristic with the whole file.
        writeResumeState(statePath, total, plan.chunkSize, probe.validator, completed)
        try {
            storage.preallocate(partial, total)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Outcome.Failure(e.toFileSystemError())
        }

        val writer = try {
            storage.openPositionedWriter(partial)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Outcome.Failure(e.toFileSystemError())
        }

        val counted = AtomicLong(base)
        val nextChunk = AtomicInteger(0)
        val reclaimed = ConcurrentLinkedQueue<Int>()
        val chunkBytes = AtomicLongArray(plan.chunkCount)
        val chunkAttempts = AtomicIntegerArray(plan.chunkCount)
        val completedCount = AtomicInteger(completed.cardinality())
        val error = AtomicReference<DownloadError?>(null)
        val lastActivity = AtomicLongArray(connections.coerceAtLeast(1))
        val liveWorkers = AtomicInteger(0)

        try {
            supervisorScope {
                val workerCount = minOf(connections, plan.chunkCount)
                // The handles must be the launched coroutines' own jobs: a standalone Job() never
                // completes by itself, so joining one would block forever.
                val jobs = Array(workerCount) { index ->
                    launch {
                        liveWorkers.incrementAndGet()
                        try {
                            worker(
                                index = index,
                                request = request,
                                plan = plan,
                                total = total,
                                writer = writer,
                                completed = completed,
                                nextChunk = nextChunk,
                                reclaimed = reclaimed,
                                chunkBytes = chunkBytes,
                                chunkAttempts = chunkAttempts,
                                counted = counted,
                                completedCount = completedCount,
                                error = error,
                                lastActivity = lastActivity
                            )
                        } finally {
                            liveWorkers.decrementAndGet()
                        }
                    }
                }

                val reporter = launch { reportProgress(onProgress, counted, total) }
                val saver = launch {
                    persistPeriodically(statePath, total, plan.chunkSize, probe.validator, completed, writer)
                }
                val watchdog = launch { supervise(this, jobs, lastActivity, counted, chunkBytes, liveWorkers, error) }

                jobs.forEach { it.join() }
                reporter.cancel()
                saver.cancel()
                watchdog.cancel()
            }
        } finally {
            runCatching { writer.force(true) }
            runCatching { writer.close() }
        }

        writeResumeState(statePath, total, plan.chunkSize, probe.validator, completed)

        val failure = error.get()
        val allDone = completedCount.get() >= plan.chunkCount
        return when {
            failure != null -> Outcome.Failure(failure)
            !allDone -> Outcome.Failure(DownloadError.NetworkFailure("Every connection stopped with chunks outstanding"))
            else -> {
                runCatching { storage.delete(statePath) }
                // The reporter runs on a timer; make sure the UI is told the file is complete
                // instead of leaving it one interval short of the total.
                onProgress(Progress(total, total))
                Outcome.Success(total, total)
            }
        }
    }

    private suspend fun worker(
        index: Int,
        request: DownloadRequest.Http,
        plan: HttpTransferPlanner.Plan,
        total: Long,
        writer: PositionedWriter,
        completed: CompletedChunks,
        nextChunk: AtomicInteger,
        reclaimed: ConcurrentLinkedQueue<Int>,
        chunkBytes: AtomicLongArray,
        chunkAttempts: AtomicIntegerArray,
        counted: AtomicLong,
        completedCount: AtomicInteger,
        error: AtomicReference<DownloadError?>,
        lastActivity: AtomicLongArray
    ) {
        var inFlight = -1
        try {
            while (error.get() == null) {
                if (inFlight < 0) {
                    inFlight = claim(plan, completed, nextChunk, reclaimed, completedCount) ?: return
                }
                val chunk = inFlight
                inFlight = -1
                if (completed.isDone(chunk)) continue

                val start = plan.startOf(chunk)
                val end = plan.endOf(chunk, total)
                var position = start
                lastActivity.set(index, System.nanoTime())

                val result = client.stream(
                    request = request,
                    start = start,
                    end = end,
                    onHead = {},
                    sink = { buffer, length ->
                        limiter.acquire(length)
                        writer.writeAt(position, buffer, 0, length)
                        position += length
                        metrics.recordBytes(length.toLong())
                        chunkBytes.addAndGet(chunk, length.toLong())
                        counted.addAndGet(length.toLong())
                        lastActivity.set(index, System.nanoTime())
                    }
                )

                when (result) {
                    is HttpNetworkClient.StreamResult.Success -> markDone(chunk, completed, completedCount)

                    HttpNetworkClient.StreamResult.RangeIgnored -> {
                        // A CDN that answers some requests with 200 cannot be segmented safely.
                        discard(chunk, chunkBytes, counted)
                        error.compareAndSet(null, DownloadError.RangeUnsupported)
                        return
                    }

                    is HttpNetworkClient.StreamResult.Truncated -> {
                        discard(chunk, chunkBytes, counted)
                        requeue(chunk, result.error(), chunkAttempts, reclaimed, error)
                    }

                    is HttpNetworkClient.StreamResult.Failed -> {
                        discard(chunk, chunkBytes, counted)
                        requeue(chunk, result.error, chunkAttempts, reclaimed, error)
                    }

                    is HttpNetworkClient.StreamResult.Redirected -> {
                        discard(chunk, chunkBytes, counted)
                        requeue(chunk, DownloadError.NetworkFailure("Redirect chain changed mid-transfer"), chunkAttempts, reclaimed, error)
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stalled-worker drop: hand the unfinished chunk back to the pool.
            if (inFlight >= 0) {
                discard(inFlight, chunkBytes, counted)
                reclaimed.offer(inFlight)
            }
            throw e
        } catch (e: Exception) {
            // A write that failed (disk full, channel closed) must end the transfer as a normal
            // failure instead of tearing down the supervisor scope with an unhandled exception.
            if (inFlight >= 0) discard(inFlight, chunkBytes, counted)
            error.compareAndSet(null, e.toFileSystemError())
        }
    }

    private fun HttpNetworkClient.StreamResult.Truncated.error(): DownloadError =
        DownloadError.NetworkFailure("Chunk ended early: $received of $expected bytes")

    private fun requeue(
        chunk: Int,
        cause: DownloadError,
        chunkAttempts: AtomicIntegerArray,
        reclaimed: ConcurrentLinkedQueue<Int>,
        error: AtomicReference<DownloadError?>
    ) {
        if (!cause.isRetryable()) {
            error.compareAndSet(null, cause)
            return
        }
        metrics.recordChunkRetry()
        if (chunkAttempts.incrementAndGet(chunk) > MAX_CHUNK_ATTEMPTS) {
            error.compareAndSet(null, cause)
            return
        }
        reclaimed.offer(chunk)
    }

    private fun discard(chunk: Int, chunkBytes: AtomicLongArray, counted: AtomicLong) {
        counted.addAndGet(-chunkBytes.getAndSet(chunk, 0L))
    }

    private fun claim(
        plan: HttpTransferPlanner.Plan,
        completed: CompletedChunks,
        nextChunk: AtomicInteger,
        reclaimed: ConcurrentLinkedQueue<Int>,
        completedCount: AtomicInteger
    ): Int? {
        if (completedCount.get() >= plan.chunkCount) return null
        reclaimed.poll()?.let { return it }
        while (true) {
            val index = nextChunk.getAndIncrement()
            if (index >= plan.chunkCount) return null
            if (!completed.isDone(index)) return index
        }
    }

    private fun markDone(chunk: Int, completed: CompletedChunks, completedCount: AtomicInteger) {
        if (completed.markDone(chunk)) completedCount.incrementAndGet()
    }

    private suspend fun reportProgress(
        onProgress: suspend (Progress) -> Unit,
        counted: AtomicLong,
        total: Long
    ) {
        while (true) {
            delay(PROGRESS_TICK_MS.milliseconds)
            onProgress(Progress(counted.get(), total))
        }
    }

    private suspend fun persistPeriodically(
        statePath: Path,
        total: Long,
        chunkSize: Long,
        validator: String?,
        completed: CompletedChunks,
        writer: PositionedWriter
    ) {
        var lastWritten = -1
        while (true) {
            delay(RESUME_FLUSH_INTERVAL)
            // Cardinality, not length: a lower chunk finishing while a higher one is already set
            // leaves the highest set bit untouched, and the update would never be persisted.
            val seen = completed.cardinality()
            if (seen == lastWritten) continue
            lastWritten = seen
            // The sidecar promises that the bytes are in the file, so the file has to reach the
            // disk first: after a power loss a journaling filesystem can keep the renamed sidecar
            // while the data blocks it describes are gone.
            runCatching { writer.force(true) }
            writeResumeState(statePath, total, chunkSize, validator, completed)
        }
    }

    /**
     * Drops a connection that stopped delivering bytes while others keep going, so its chunk can
     * be picked up by someone faster. The last living worker is never dropped: a genuinely dead
     * socket is already covered by the client's read-inactivity timeout.
     */
    private suspend fun supervise(
        scope: CoroutineScope,
        jobs: Array<Job>,
        lastActivity: AtomicLongArray,
        counted: AtomicLong,
        chunkBytes: AtomicLongArray,
        liveWorkers: AtomicInteger,
        error: AtomicReference<DownloadError?>
    ) {
        val stallNs = settings.httpStalledConnectionSeconds * 1_000_000_000L
        var lastCounted = counted.get()
        var lastChangeNs = System.nanoTime()

        while (scope.isActive) {
            delay(WATCHDOG_INTERVAL_MS.milliseconds)
            val now = System.nanoTime()

            if (error.get() != null) {
                jobs.forEach { if (it.isActive) it.cancel() }
                return
            }

            val value = counted.get()
            if (value != lastCounted) {
                lastCounted = value
                lastChangeNs = now
                continue
            }
            if (now - lastChangeNs < stallNs) continue
            if (liveWorkers.get() <= 1) continue

            var victim = -1
            var victimActivity = Long.MAX_VALUE
            for (index in jobs.indices) {
                if (!jobs[index].isActive) continue
                val activity = lastActivity.get(index)
                if (activity < victimActivity) {
                    victimActivity = activity
                    victim = index
                }
            }
            if (victim >= 0) {
                logger.warn(
                    "Connection {} delivered nothing for over {}s; dropping it and re-queueing its chunk",
                    victim,
                    settings.httpStalledConnectionSeconds
                )
                lastChangeNs = now
                metrics.recordStalledWorkerDrop()
                jobs[victim].cancel()
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun chunkCountFor(total: Long, chunkSize: Long): Int =
        ((total + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)

    /**
     * Chunk completion bookkeeping shared by every connection worker.
     *
     * [BitSet] is documented as unsafe for concurrent use: a read that races a grow can throw or
     * miss a bit, and this state is written by one worker per connection while the claim loop, the
     * periodic saver and the final sidecar write all read it. One lock covers every access, and it
     * is taken once per chunk rather than once per buffer, so it stays off the hot path.
     */
    internal class CompletedChunks(chunkCount: Int) {
        private val bits = BitSet(chunkCount)

        fun isDone(chunk: Int): Boolean = synchronized(bits) { bits.get(chunk) }

        /** True when this chunk had not been accounted for yet. */
        fun markDone(chunk: Int): Boolean = synchronized(bits) {
            if (bits.get(chunk)) false else {
                bits.set(chunk)
                true
            }
        }

        fun cardinality(): Int = synchronized(bits) { bits.cardinality() }

        fun length(): Int = synchronized(bits) { bits.length() }

        fun snapshot(): BitSet = synchronized(bits) { bits.clone() as BitSet }
    }

    private fun sizeOf(path: Path): Long = runCatching {
        if (Files.isRegularFile(path)) Files.size(path) else 0L
    }.getOrDefault(0L)

    private fun readResumeState(path: Path): HttpResumeState? = runCatching {
        if (!Files.isRegularFile(path)) return null
        Json.decodeFromString<HttpResumeState>(Files.readString(path))
    }.getOrNull()

    private fun writeResumeState(
        path: Path,
        total: Long,
        chunkSize: Long,
        validator: String?,
        completed: CompletedChunks
    ) {
        val state = HttpResumeState.of(total, chunkSize, validator, completed.snapshot())
        try {
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(tmp, Json.encodeToString(HttpResumeState.serializer(), state))
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            logger.debug("Could not persist the HTTP resume state: {}", e.message)
        }
    }

    private fun Throwable.toFileSystemError(): DownloadError = when {
        this is IOException && message?.contains("No space left", ignoreCase = true) == true -> DownloadError.DiskFull
        this is AccessDeniedException -> DownloadError.Unauthorized
        else -> toDownloadError()
    }

    private companion object {
        const val PROGRESS_INTERVAL_NS = 250_000_000L
        const val PROGRESS_TICK_MS = 400L
        const val WATCHDOG_INTERVAL_MS = 2_000L
        const val MAX_CHUNK_ATTEMPTS = 4
        const val RANGE_NOT_SATISFIABLE = 416
        val RESUME_FLUSH_INTERVAL: Duration = 15.seconds
    }
}
