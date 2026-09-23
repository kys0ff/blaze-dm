package org.blaze.download.api

import kotlinx.coroutines.flow.Flow

/**
 * The application-level download API - the single boundary every client codes against.
 *
 * GUI, CLI and any transport (in-process, HTTP, IPC) express intent *only* through this interface, and
 * one implementation ([DownloadsApi] backed by the download engine) owns all lifecycle serialization and
 * race-safety. Nothing here exposes coroutine scopes, worker pools, chunk layout, the HTTP/torrent client
 * or the scheduler: an implementation can be swapped wholesale without touching a client.
 *
 * Error contract: every operation throws [DownloadApiException] with a stable [ApiErrorCode]; it never
 * leaks engine exception types. All mutating operations are safe to call concurrently - the service
 * serializes them against the engine's own lifecycle lock.
 */
interface DownloadsApi {

    /** What this build can do; read this before assuming a capability exists. */
    val capabilities: ApiCapabilities

    /** The API version this instance serves. */
    val apiVersion: Int get() = ApiVersion.CURRENT

    /** A consistent snapshot of all downloads, newest first. */
    fun snapshot(): List<DownloadDto>

    /** A cold [Flow] of the full list; re-emits whenever any download changes. */
    fun observe(): Flow<List<DownloadDto>>

    /** A cold [Flow] of lifecycle events; progress events are coalesced server-side. */
    fun events(): Flow<DownloadEvent>

    suspend fun get(id: String): DownloadDto?

    /**
     * Queues (and, when the request says so, starts) a new download and returns its initial view.
     *
     * [request] carries [NewDownloadRequest.credentials] only transiently: they reach the engine's
     * in-memory request, never the returned DTO, and never disk. Duplicate-submit protection is applied
     * via [NewDownloadRequest.idempotencyKey] (and, where unambiguous, an in-flight source match).
     */
    suspend fun create(request: NewDownloadRequest): DownloadDto

    suspend fun start(id: String)
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun cancel(id: String)
    suspend fun retry(id: String)
    suspend fun remove(id: String, deleteFiles: Boolean = false)

    /**
     * Re-ranks an existing download. Succeeds as a no-op only when the engine reports
     * [PrioritySupport.EXPLICIT]; a FIFO build rejects it rather than silently ignoring the request.
     */
    suspend fun setPriority(id: String, priority: DownloadPriority)

    /**
     * Probes [request]'s source (a HEAD/range check for HTTP, a metadata fetch for torrent) and returns a
     * preview without committing to a transfer. Returns null when the source cannot be described.
     */
    suspend fun probe(request: NewDownloadRequest): DownloadMetadataDto?

    /**
     * Reads a moved artifact at [path] as a candidate portable download and returns a display-only summary
     * (never any secret material), or null when it is not a Blaze portable artifact.
     */
    suspend fun detectPortable(path: String): PortableInfoDto?

    /**
     * Reconstructs the portable artifact at [artifactPath] into [destinationDir] so it resumes from the
     * data already on disk. Returns the new download id, or null when [artifactPath] is not portable.
     */
    suspend fun importPortable(artifactPath: String, destinationDir: String): String?

    /** Releases resources; only transports/hosts that own the service lifetime call this. */
    suspend fun shutdown()
}
