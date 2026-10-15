# Download Engine – Progress Log

Running log required by `PROMPT.md`. Re-read at the start of every phase.

---

## Phase 1 – Investigation (this document)

Status: **complete, awaiting review**. No code changes have been made.
Baseline commit: `master` (16 unpushed commits ahead of `origin/master`).

Everything below was read in this session. Line references are exact.

### 1. Module structure and entry points

`download-engine` is one Gradle Kotlin/JVM library (`build.gradle.kts:1`) on Java 25,
Kotlin 2.4.21, Ktor 3.6.0 (`ktor-client-core` + `cio` + `java` + `mock`), coroutines 1.11.0,
`serialization-json` 1.11.0, and `com.github.atomashpolskiy:bt` 1.10
(`bt-core`, `bt-http-tracker-client`, `bt-dht`). No testcontainers, no JUnit5 extensions,
no Ktor server module on the classpath.

Package layout (47 main files, 49 test files):

| Package | Role |
|---|---|
| `api` | Public types: `DownloadEngine`, `DownloadRequest`, `DownloadTask`, `DownloadState`, `DownloadError`, `DownloadPriority`, `Inspection`, `Portable*`. |
| `core` | `DownloadManager` (819 lines) — implements `DownloadEngine`, owns scope, `HttpClient`, tasks map, persistence, scheduler. |
| `execution` | `DownloadExecutorImpl` (per-attempt driver), `HttpDownloadCoordinator` (HTTP data path), `HttpTransferPlanner` (chunk layout). |
| `network` | `HttpNetworkClient` (Ktor wrapper), `BandwidthLimiter`, `TorrentNetworkClient` (bt wrapper), `TorrentRuntimeProvider`, `TorrentNetworkEvent`. |
| `hls` | `HlsDownloader`, `HlsPlaylist`, `HlsPlaylistParser`. |
| `archive` | `RemoteZip*`, `HttpRemoteByteSource`, `ZipCentralDirectory` (range-based ZIP browsing). |
| `storage` | `FileStorage`, `DefaultFileStorage` (RAF + FileChannel), `PositionedWriter`, `TorrentContentStorage` (bt `Storage` impl). |
| `persistence` | `DownloadRepository` (JSON snapshot), `DownloadRecord`, `HttpResumeState` (per-chunk bitset sidecar). |
| `portable` | Cross-machine resume: `PortableStore`, `PortableFormat`, `PortableManifest`, `TorrentPortableFile`, `PortableImport`, `PortableResume`. |
| `scheduler` | `DownloadScheduler` – concurrency cap, queue ordering, pause/cancel. |
| `settings` | `DownloadSettings`, `EngineSettingsRepository`. |
| `retry` | `RetryPolicy`, `isRetryable`. |
| `metrics` | `EngineMetrics` — lock-free counters. |

### 2. Data flow

```
DownloadManager.enqueue() -> DownloadScheduler.processQueue()
    -> DownloadExecutorImpl(channelFlow)
        -> HttpDownloadCoordinator / HlsDownloader / TorrentNetworkClient
        -> emits DownloadTask updates through the Flow
    -> DownloadScheduler collects into tasks: StateFlow + onTaskUpdated callback
    -> DownloadManager persists via a debounced CONFLATED channel (1 s) writing
       the full list to disk as `downloads.json` (write-temp-then-atomic-move).
```

### 3. Ktor client configuration

`DownloadManager.createDefaultHttpClient()` (lines 148–155):

- Engine: **`Java`** (JDK `java.net.http.HttpClient`). Reason, spelled out in the KDoc: CIO opens a fresh socket per request, so a segmented download would pay a full TCP (+ TLS) handshake per chunk. Verified. Good decision.
- `install(HttpTimeout)`: `requestTimeoutMillis = INFINITE`, `connectTimeoutMillis = 15_000`, `socketTimeoutMillis = 60_000` (the KDoc acknowledges that Java engine has no per-socket read-inactivity timeout and the watchdog covers that case).
- `followRedirects = false` — redirects are handled manually inside `HttpNetworkClient.stream`/`probe` so that `Range` re-plays correctly against the new URL and cross-host leaks are avoided. Correct.
- **No explicit executor tuning.** The JDK client is created without `HttpClient.newBuilder().executor(...)`, so it uses the JDK's default (a per-client cached thread pool). Ktor wraps it. Not a bottleneck today but not nothing.
- **No HTTP/2 explicit setting.** The JDK engine negotiates h2 by default over TLS, so segmented downloads against a h2-capable host can multiplex; no config option is exposed. The engine `engine` block on `ktor-client-java` supports `HttpClientConfig` (proxy, authenticator, version, priority table); none of these are used today.
- **`Accept-Encoding: identity`** is sent explicitly on every request (`HttpNetworkClient.applyCommonHeaders`, line 283). Correct for ranged transfers; blocks gzip-benefit for single-stream small files, but that trade is fine.
- **No `HttpRequestRetry`, no cookie/storage, no content-negotiation plugins installed.** Retry is done by the coordinator/watchdog. Cookies are not supported at all — a session-cookie-protected resource cannot currently be downloaded (needs `UserAgent`, `Cookies` plugin).
- **No per-host connection cap in the client pool.** JDK HttpClient has no exposed knob; concurrency is bounded by the worker count. See §11 for a subtle interaction.

The Ktor client is a singleton owned by `DownloadManager` and shared across every executor and every download. Good.

### 4. Concurrency model

- **Scope**: engine takes an externally owned `CoroutineScope`. `DownloadManager` launches its persist loop and settings-collect on that scope. Nothing is `SupervisorJob`-wrapped by the manager itself; supervisors are used locally where needed (`supervisorScope` in the segmented coordinator and around the per-worker in-flight stream).
- **Dispatchers**: HTTP path is `withContext(Dispatchers.IO)` at the top of `HttpDownloadCoordinator.download`. Torrent path is inside `Dispatchers.IO` via `flowOn`. Scheduler / workers all inherit `IO`. Nothing runs on `Dispatchers.Default`.
- **Shared mutable state**, HTTP segmented path:
  - `AtomicLong counted`, `AtomicInteger nextChunk`, `AtomicIntegerArray chunkAttempts`, `AtomicLongArray chunkBytes`, `AtomicLongArray lastActivity`, `AtomicReferenceArray<Job?> inFlightStream`, `AtomicInteger liveWorkers`, `AtomicReference<DownloadError?> error`, `ConcurrentLinkedQueue<Int> reclaimed`.
  - `CompletedChunks` wraps `BitSet` inside a `synchronized(bits)` monitor (lines 777–795). Every `isDone`, `markDone`, `cardinality`, `length`, `snapshot` takes the lock. Fine for correctness, cheap because it's once per chunk, not once per buffer.
- **Torrent `RuntimeProvider`**: `synchronized(lock)` around `acquire`/`release`. Reference-counted with a 60 s idle grace via a `ScheduledExecutorService`. Sound.
- **BandwidthLimiter**: `synchronized(lock)` around a leaky-bucket nanosecond computation, then a `delay(...)` **outside** the lock. Called on the hot write path once per 128 KiB buffer (see §7). Lock is short and uncontended in practice; not the bottleneck.
- **Watchdog (HTTP)**: single coroutine, ticks every 2 s. Cancels only the child Job of the in-flight stream, not the worker. Well designed.

**Blocking calls on the wrong dispatcher?**
- Torrent `awaitTorrentSettled` uses `Thread.sleep` inside a `withContext(NonCancellable + Dispatchers.IO)` block. Correct dispatcher but blocks an IO thread for up to `SETTLE_ROUNDS × (SETTLE_HOLD_MS + poll)` ≈ 500 ms – 1 s. Since the scheduler holds `queueMutex` while it joins the pause job, this is a real global-pause latency cost (acknowledged in the code comment at `TorrentNetworkClient.kt:702`).
- `TestHttpServer` spawns a Thread per connection, which is fine for tests.
- `DefaultFileStorage.writeAt` calls `channel.write(...)` synchronously on `Dispatchers.IO`. OK.

**No blocking-on-default dispatcher** was found.

### 5. Segmentation and resume (HTTP)

`HttpDownloadCoordinator.download`:

1. If `httpAccelerationEnabled && connections > 1`: run `client.probe(request)` which does HEAD + optional `Range: bytes=0-0` GET. Decide segmented vs single-stream by `probe.acceptsRanges && probe.totalBytes >= httpMinParallelSizeBytes`.
2. If disabled but a sidecar exists: still run the segmented path so a resume of a previously-segmented download doesn't corrupt (this is the right call and is called out in code comments).
3. Otherwise plain single-stream with an offset-based retry loop.

Segmented path (`segmented`, lines 300–522):
- Layout chosen by `HttpTransferPlanner.plan(total, connections, chunkSizeMb * 1 MiB)`.
  - `MIN_CHUNK = 1 MiB`, `MAX_CHUNK = 64 MiB`, `MAX_CHUNKS = 20 000`.
  - Chosen chunk = `min(8 MiB preferred, max(1 MiB, totalBytes / (connections*8)))`, floored to keep chunk count ≤ 20 000. This yields roughly `8 × connections` chunks.
- **`CompletedChunks` is a bitset of chunk indices**. Only whole chunks are ever marked done. Crash mid-chunk re-downloads that chunk (idempotent positioned writes make this safe).
- Resume validation: sidecar is reused iff `persisted.totalBytes == total && persisted.validator == probe.validator && sizeOf(partial) >= total`. A truncated partial invalidates the sidecar → hard restart with `onRestart()` fired.
- **Validator is a single string** (ETag, or Last-Modified as fallback). No `If-Range` request is ever sent. When the file rotates mid-transfer, we discover it after the fact via a subsequent probe and restart. This is not optimal (bandwidth wasted) but it is correct.
- **Persist cadence**: 15 s tick writes: `writer.force(true)` then atomic sidecar write, and then a portable checkpoint. On stop boundaries (pause, fail, complete) the sidecar is written once more synchronously.
- Preallocation: `storage.preallocate(partial, total)` uses `RandomAccessFile.setLength(size)` — sparse on NTFS/APFS/ext4. That gives us the "grow once, write anywhere" behaviour but is **not** a real disk reservation. See §11 for NTFS behaviour.

### 6. Disk I/O

- **`FileChannel.write(ByteBuffer.wrap(buffer, offset+written, length-written), position)`** — positional writes, no global lock, safe for concurrent writers to disjoint regions (`DefaultFileStorage.ChannelPositionedWriter`, lines 74–89).
- **Buffer per stream** is `ByteArray(128 * 1024)` allocated inside `HttpNetworkClient.streamAt` (line 255). It is one 128 KiB array *per HTTP request*, not per chunk iteration. On a segmented download of, say, 100 chunks / 4 workers, we allocate ~100 buffers total. Not a GC hazard.
  - **Missing**: reuse of a per-worker buffer pool. Every chunk still allocates a new `ByteArray`. Small (~128 KiB × chunk count), not a top-tier bottleneck, but the PROMPT explicitly asks for "pooled, reusable buffers".
- **No `ByteBuffer` direct buffer use**; `ByteArray` + `wrap` costs an allocation and is safe. Would a direct buffer help? Only if we could amortise it across many writes. Given `readAvailable(ByteArray)` in Ktor's API, a direct buffer would require `ByteReadChannel.readRemaining` into a `BufferByteChannel`, which is more awkward. Leave as-is unless measurement says otherwise.
- **No batching between read → write** — every buffer from the socket goes straight into the file channel. Fine.
- **No separate writer thread or bounded queue between socket read and disk write** — every worker coroutine does both. A stalled disk currently stalls the socket on the same coroutine (PROMPT §Phase-3 "decouple network reads from disk writes with a bounded channel/queue" is **not done**).
- **`force(true)`** is called (a) by the periodic saver every 15 s, (b) once in `finally` at the end of a segmented run, (c) by the single-stream path not at all. Reasonable.
- **Finalization**: `storage.move(partial, destination)` in `DownloadExecutorImpl.executeHttp` after success — an atomic `Files.move REPLACE_EXISTING`. `partial -> destination` on the same volume. Good.

### 7. Hot-path cost per 128 KiB

For each read the HTTP worker executes:
1. `limiter.acquire(length)` — one `AtomicLong.get`, one `synchronized`, one division, one `delay` if needed. Small.
2. `writer.writeAt(position, buffer, 0, length)` — allocates one `ByteBuffer.wrap`, calls `FileChannel.write` (loop for partial writes).
3. `metrics.recordBytes(length.toLong())` — one atomic `addAndGet`. Cheap.
4. `chunkBytes.addAndGet(chunk, length.toLong())` — atomic.
5. `counted.addAndGet(length.toLong())` — atomic.
6. `lastActivity.set(index, System.nanoTime())` — atomic write.

Nothing pathological. `System.nanoTime()` on JDK 25 is ~20–25 ns. Total per 128 KiB ≈ ~1–2 µs of overhead. Not a bottleneck.

### 8. Retry / resilience

- **Chunk-level retry**: `MAX_CHUNK_ATTEMPTS = 4`, chunk is re-queued via `ConcurrentLinkedQueue reclaimed`, another worker picks it up. **No exponential backoff at the chunk level.** A single retry storm against a throttling server will hammer it.
- **Task-level retry**: `DefaultRetryPolicy.getNextDelay` — settings-driven, `baseDelay * 2^retryCount`, capped at 16 shifts, no jitter. **`DownloadSettings.exponentialBackoff` is `false` by default** (line 67) — meaning default task retry is a fixed `retryDelaySeconds = 5` for `maxRetries = 3`. Very short against a 429/503 host.
- **HTTP status handling** in `HttpNetworkClient.errorForStatus`: 404/410 → NotFound (fatal), 401/403/402 → Unauthorized (fatal), 416 → RangeUnsupported (fatal), 429/503 → generic NetworkFailure (retryable), 5xx → NetworkFailure (retryable).
- **`Retry-After` header is ignored entirely.** For a 429/503 host that says "wait 30 s", we currently retry after 5 s. This is a real quality gap.
- **Stall detection**: per-slot activity timestamps, watchdog cancels the in-flight child Job, chunk gets re-queued. Well-implemented.
- **Transient network errors** are retried only via chunk requeue and task-level retry. There is **no retry against `Retry-After`, no backoff at the chunk level, no circuit breaker for a per-IP connection limit that returns 403** (see §11).

### 8b. Pause / cancel correctness

- Pause: `DownloadScheduler.pause(id)` holds `queueMutex`, calls `stopJobLocked(id)` = `job.cancelAndJoin()`, and only then flips state to `Paused`. HTTP worker unwinds through `CancellationException` naturally. Segmented writer's `finally { runCatching { writer.force(true) }; runCatching { writer.close() } }` (lines 487–490) makes the on-disk state durable before we return. `writeResumeState` runs *after* that, and `portableCheckpoint()` also runs. Correct.
- Cancel: identical teardown.
- Torrent: `awaitTorrentSettled` on NonCancellable IO dispatcher (see §4) — necessary to avoid the "Torrent already registered" race.

### 9. Progress & speed

- `PROGRESS_INTERVAL_NS = 250 ms` throttle on the single-stream hot path; segmented path uses a 400 ms periodic reporter coroutine. Both fine.
- **Speed smoothing**: `HttpProgress` EMA with `ALPHA = 0.3`, minimum sample interval 200 ns — effectively no floor. Called from every `send(updateTask(...))` (i.e. every 400 ms tick of the periodic reporter, plus every single-stream throttle). Reasonable.
- **ETA** computed once per emission as `(total - downloaded) / speed`. Cheap.
- Progress reports are `send` inside `channelFlow`, which is unbuffered — every emission suspends until the collector (`DownloadScheduler.launchLocked { ... collect { ... } }`) picks it up. Under heavy CPU this could back-pressure the reporter coroutine, not the workers. OK.

### 10. HLS

`HlsDownloader` (455 lines) + `HlsPlaylistParser` (284) + `HlsPlaylist` (81).

Parses:
- `#EXTM3U` magic check.
- Master variants with `BANDWIDTH`/`AVERAGE-BANDWIDTH`, `RESOLUTION`, `CODECS`, `LANGUAGE`, `NAME`.
- Media segments with `#EXTINF`, per-segment `#EXT-X-KEY` (METHOD/URI/IV, including key rotation), `#EXT-X-DISCONTINUITY`, `#EXT-X-MEDIA-SEQUENCE`, `#EXT-X-TARGETDURATION`, `#EXT-X-ENDLIST`, `#EXT-X-SESSION-KEY`.
- Attribute-list parser is quote-aware (`parseAttributeList`, lines 237–272).

Missing / wrong (relative to PROMPT Phase 4):
- **No `EXT-X-MAP` (fMP4 init segment) and no `EXT-X-BYTERANGE`.** `HlsDownloader.detectContainer` sniffs the first segment bytes and if it's fMP4 refuses with `Outcome.Unsupported` (see lines 132–138 and 371–372). So fMP4 HLS is intentionally unsupported today.
- **No `EXT-X-MEDIA` (audio/subtitle renditions).** `collectVariants` only handles `EXT-X-STREAM-INF`. Alternate-audio masters lose everything but video.
- **No `EXT-X-I-FRAME-STREAM-INF`, no `EXT-X-MEDIA-SESSION`, no `EXT-X-DATERANGE`, no `EXT-X-PROGRAM-DATE-TIME`.** Fine to skip the date-range tags.
- **Live streams are refused** at the download entry point (`isLive` short-circuits to `Outcome.Unsupported`). The PROMPT Phase 4 asks for a *simulated* live playlist with refresh, sequence tracking, no dup/skip. Not implemented, though VOD-only behaviour is defensible for a download manager.
- **Container assembly is MPEG-TS-only**. It runs a straight file-append concatenation of segments. No ffmpeg integration, no remux. `assemble` uses `BufferedOutputStream` + `Files.newInputStream(part).copyTo(os)` (351–356) — **writes every segment twice**: once when saving to `tempDir/index.part`, once when concatenating into `partial`. Disk-write cost ≈ 2× the stream size, plus tempDir must fit the full stream.
- **Segments are fetched fully into memory** (`fetchBytes` collects into a `ByteArrayOutputStream`, then `Files.write(target, result.bytes)`). HLS segments are typically 1–10 MiB, so this is fine, but a rogue `#EXTINF:600` segment with a 32 MiB cap is silently rejected with `IOException("resource exceeds ... bytes")`.
- **No `#EXT-X-ALLOW-CACHE`, no `EXT-X-KEYFORMAT` beyond DRM detection** — fine.
- **No resume across segment boundaries** *beyond* the "file exists with size > 0 → skip" check (lines 259–264). If a segment file is partially written on crash, it is **resumed by re-downloading the whole segment**, which is correct — but the check is only `Files.size(target) > 0`. If the size is > 0 but *less than what the server now serves*, we accept it as complete. There's no expected-length record. **Bug: a truncated segment file left over from a crash will be silently concatenated into the output**. See §11.
- **Concurrency is hard-coded to 4** (`maxConcurrency: Int = 4`), not derived from `settings.maxConnectionsPerDownload`. **`BandwidthLimiter` is applied but there is no per-segment retry backoff** — retry loop is a bare `while(true)` with 3 attempts and no delay (`downloadSegments`, lines 265–287). **Bug: on 429/503 or a transient reset the retry hammers immediately.**
- **No connection reuse strategy** — segments go through `HttpNetworkClient.stream` on the shared Ktor Java client, which does keep-alive. Good.
- **AES-128 CBC/PKCS5 unpadding** is JDK-only (`decryptAes128`). Correct for ordinary HLS. No SAMPLE-AES.
- **IV handling**: explicit IV per key tag is used; if absent, the media-sequence IV is derived from `mediaSequence + index`. Correct.
- **Key caching**: `keyCache = ConcurrentHashMap<String, ByteArray?>` per run, no cross-run persistence. Correct for key rotation.
- **`fetchBytes` does not use the `expected length` from the response** — it accepts `StreamResult.Truncated` as success and returns partial bytes (line 338–341). This can silently bake truncated segments into the output. **Bug.**

### 11. Torrent (bt)

`TorrentRuntimeProvider` + `TorrentNetworkClient` + `TorrentContentStorage`.

Runtime:
- One process-wide `BtRuntime`, refcounted via `TorrentRuntimeProvider.GLOBAL`, 60 s idle grace, recreated on peer-budget fingerprint change (unless clients are active).
- Modules (after Phase 5): `DHTModule` (router bootstrap), `HttpTrackerModule`, **`UdpTrackerModule`** (new), **`LocalServiceDiscoveryModule`** (new), `PeerExchangeModule`.

> **Phase-5 correction.** This section originally asserted several `bt` capabilities that do not exist
> in 1.10, framed as "missing" work. The claims were checked against `bt-core-1.10-sources.jar` and
> corrected below. What is real is wired now; what does not exist is stated as a limitation.
- **UDP trackers — a real gap, now closed with NO new dependency.** The audit said to add `bt-upnp-tracker-client`; **that artifact does not exist.** `bt.tracker.udp.UdpTracker` + `UdpTrackerFactory` ship inside `bt-core` (already a dependency) but are never ServiceLoader-registered, so `udp://` announces resolved to no tracker. `UdpTrackerModule` binds the factory for the `udp` scheme. Most public torrents announce over UDP, so this is the highest-value Phase-5 fix. `logTrackers` now counts `udp://` as usable.
- **Protocol encryption (MSE) — real and now enabled.** The audit said MSE is "behind `ExtensionsModule` (may be a plugin)". Wrong: it is in `bt-core` (`Config.encryptionPolicy`, `mseDisabled`). We set `PREFER_ENCRYPTED` (encrypted handshake first, plaintext fallback) so we gain reachability/privacy without locking out legacy peers the way `REQUIRE_ENCRYPTED` would.
- **Local Service Discovery — real and now wired.** The audit said "LSD is not in the library"; `bt.peer.lan.LocalServiceDiscoveryModule` exists in `bt-core`. It is wired; multicast-scoped (TTL 1), per-announce failures are caught and logged, so it degrades gracefully when multicast is unavailable.
- **µTP (BEP 57), web seeds / getright (BEP 19), super seeding — genuinely absent.** `bt` 1.10 has no `WebSeed` class (the earlier `WebSeedService` mention was fabricated) and no µTP. These stay honest limitations; nothing to wire.

`applyPerformanceConfig` (lines 147–196):
- `maxPeerConnections` from settings (default 200).
- `maxConcurrentlyActivePeerConnectionsPerTorrent = min(maxPeerConnections, 60).coerceAtLeast(10)`.
- `maxSimultaneouslyAssignedPieces = (maxPeerConnections / 8).coerceIn(4, 20)`.
- `transferBlockSize = 16 KiB` — spec-correct (BEP-3 cap).
- `maxOutstandingRequests = 250` — **this is `bt`'s own default** (`Config.java` line 109), not a Blaze-introduced over-setting. The audit's "reduce to 100 for strict peers" is a hypothesis about third-party clients (libtorrent/µTorrent). Our localhost `bt`↔`bt` harness cannot exercise it — `TorrentSwarmBenchmark` transfers 0 bytes in 90 s here (in-process peers don't connect over local discovery), and even a completing run would not reproduce external strictness. Left at the library default and labelled an **unmeasured estimate**, not changed on a guess (A3).
- `maxIOQueueSize = 128` — bounded, good.
- `numOfHashingThreads = availableProcessors.coerceIn(2, 8)`.
- Timeouts, bans, retry cadence: reasonable.
- **Piece selection / endgame — the audit was wrong; nothing to enable.** There is no `EndGameStrategy`, `ExtensionsModule`, `EndGamePieceSelectionStrategy`, `setPieceSelectionStrategy`, or `setDownloadPlanFactory` in `bt-core` 1.10 (verified by source). `Config.java` documents endgame as **built-in and automatic**. Piece priority can only be influenced via `bt.torrent.selector.PrioritizedPieceSelector`/`FilePiecePriorityMapper`, which Blaze does not currently override — and there is no bug to fix here, contrary to the original note.

Storage:
- `TorrentContentStorage` uses `bt`'s `OpenFileCache` with 256 open files, resolves paths under the destination (correctly avoiding bt's double-root default), and rejects hostile path elements.
- **No preallocation of torrent files, and `bt`'s `FileSystemStorage` offers none.** The audit claimed a `preallocate` option "via `Config.isSampledHashingEnabled` / `DefaultFileStorage` extension" — no such option exists (`FileSystemStorage`/`FileSystemStorageUnit` use a `SeekableByteChannel` and grow files on write; there is no sparse/preallocation hint). Genuine library limitation, not a flag we forgot to set.
- **No fast-resume — a hard `bt` 1.10 limitation, NOT fixable.** The audit said to enable `HashingStrategy.SAMPLED` / `bt.portability` / `SampledHashingStrategy`; **none of these classes exist** in 1.10 (`bt.data.digest` is only `Digester`/`JavaSecurityDigester`/`SHA1Digester`). On resume `bt` re-verifies every piece against the embedded hashes (see `TorrentNetworkClient` marker comment). The multi-GB restart cost is real and unavoidable without a different/patched library; we state it plainly rather than pretend to enable a non-existent toggle.

Lifecycle:
- `btClient.startAsync(callback, 1000)` — 1 s progress ticks. Fine.
- `future.awaitCompletion` bridges `CompletableFuture` to coroutine cancellation via `suspendCancellableCoroutine`. Correct.
- `awaitTorrentSettled` is the fix for the "Torrent already registered" race — well-reasoned but adds ~500 ms to every pause. Could be reduced by observing `TorrentStoppedEvent` directly instead of polling the registry, but the comment says "the library's own teardown is synchronously delivered" and the polling only fires when the natural path didn't complete. Acceptable.
- Rate meters are per-download (download & upload EMA, 0.3 α, `@Synchronized`).
- Watchdog (torrent, `launch { while(isActive) { delay(2000); ... } }`) enforces metadata timeout, no-peer timeout, stall timeout, and seeding time/ratio deadlines. Solid.

### 12. Scheduler / queue / priorities

- `maxConcurrentDownloads` (default 4) is enforced. Scheduler ticks every 1 s and on every settings change.
- Priority scaling: `DownloadPriority.connectionsFor` (0..3 weights, IDLE = 1 conn, LOW = base/2, NORMAL = base, HIGH = base × 2). Only affects connections *per download*, not bandwidth weighting across downloads. Two HIGH downloads at 8 conns + two LOW at 2 conns is roughly what you'd expect, but a HIGH download does not get more *disk* or *limiter* share. Reasonable.
- No per-host cap across concurrent downloads (PROMPT Phase 3 asks for one).

### 13. Metrics / observability

`EngineMetrics`: bytes, chunk retries, stalled drops, completed/failed/resumed, peak workers, last tail millis. Not exposed publicly through `DownloadEngine`. Debug-only.

**No structured logging**, no metrics sink, no `openmetrics`/`micrometer` integration. Log levels are set from `DownloadSettings.logLevel` — presumably wired in `download-service`. Fine for now.

### 14. Persistence

- Task list: single JSON `downloads.json` with write-temp-then-atomic-move. Full rewrite on every debounced (1 s) change. Fine for a small history (thousands of records). Not fine for tens of thousands; a proper store (SQLite, LMDB, or per-record file) is worth considering if list grows.
- HTTP resume sidecar: `.meta` next to `.part`, JSON with Base64 BitSet, atomic move. Good.
- Portable region: metadata past content in the `.part` file. Correctly aligned with the chunked resume path.

### 15. Bugs, races, waste found

Ordered roughly by severity:

1. **HTTP `Retry-After` ignored (both `429` and `503`)** — the engine will hammer a rate-limiting host on task retry every 5 s. Wasted bandwidth, may get IP banned. `HttpNetworkClient.StreamResult.Failed` currently drops the response headers entirely, so the header can't be surfaced today. (`HttpNetworkClient.kt:359–362`.)
2. **No chunk-level backoff** — `MAX_CHUNK_ATTEMPTS = 4` requeues a failed chunk immediately with **no delay** (`HttpDownloadCoordinator.worker` → `requeue` at lines 605–613 and 630–647). A flaky host with 4 workers gets 4 back-to-back retries on every failure. Wastes bandwidth, can trigger connection-limit bans.
3. **HLS truncated segments accepted on resume** — `Files.size(target) > 0` is the only completeness check (`HlsDownloader.kt:259–264`). A crash mid-write leaves a partial segment on disk that is treated as complete on the retry, then concatenated. **Produces a corrupt output file**. Also `fetchBytes` accepts `StreamResult.Truncated` as OK (line 338–341).
4. **HLS segment retry has no backoff** — `while(true)` with 3 attempts and no sleep between (`HlsDownloader.kt:270–285`). Same "hammer the CDN" problem as #1/#2 but worse.
5. **Torrent `maxOutstandingRequests = 250`** — NOT a bug: it equals `bt`'s library default. "Reduce to 100 for strict peers" is an unmeasurable hypothesis on our localhost harness (swarm transfer never completes here). Left at default; see §11.
6. **Torrent restart re-hashes every piece** — a genuine `bt` 1.10 limitation, **not** a missed `HashingStrategy.SAMPLED` toggle (that class does not exist). Cannot be fixed within this library version; documented in §11.
7. **No UDP-tracker support** — was real, now FIXED: `UdpTrackerModule` registers bt-core's `UdpTrackerFactory` for `udp://`. No dependency was added (the audit's `bt-upnp-tracker-client` does not exist).
8. **No cookies / no auth persistence**. `HttpNetworkClient` does not install the Ktor `Cookies` plugin; session-cookie-gated hosts (many direct-download links behind a login) fail with 403.
9. **No `If-Range` on resume**. When ETag/Last-Modified changes we discover it only on the *next* probe, which happens at the start of a run, not at the byte range we're fetching. We already restart at that point, so no corruption — but we throw away bytes on disk that were potentially valid under the old validator.
10. **Single-stream path does not use `PositionedWriter`.** It uses `FileWriteSession` (a `RandomAccessFile`) with `seek` + `write`, one RAF handle for the whole run. Correct but adds a syscall per write instead of one `FileChannel.write(buf, pos)`.
11. **`HttpNetworkClient.streamAt` allocates a fresh 128 KiB `ByteArray` per HTTP request** (line 255). Fine but not pooled.
12. **`HttpNetworkClient.probe` performs HEAD *plus* a `bytes=0-0` GET on every run.** For very short-lived resumptions this is 2 round-trips before any real work. It's needed for correctness (the "sometimes-lying Accept-Ranges" case), but we could cache the answer per (url, etag, session) to skip the probe on rapid restarts.
13. **`followRedirects = false` is applied to the whole client, so probe/stream re-implement redirect handling** correctly but they **do not preserve POST body or auth headers across cross-origin redirects** (this is actually the safe default). Nothing to change here — noted for the record.
14. **Segmented partial files are preallocated via `setLength(size)`** which on NTFS is sparse. A crash mid-transfer leaves a full-sized sparse file; if `contentLength(partial)` misreads the portable region on some recovery path, the "usable" check at `HttpDownloadCoordinator.kt:321` uses `sizeOf(partial) < total` to detect truncation, which is correct for sparse. Just worth documenting.
15. **`BandwidthLimiter.acquire` runs a `synchronized` block on every 128 KiB write** — uncontended in practice, but it *is* a monitor lock in the hot path. If we ever go to 32 workers, revisit.
16. **HLS `assemble` reads every segment file and appends it to `partial`.** Disk write cost ≈ 2× the total stream size. Fixable by writing each segment to a byte-offset in the destination partial as it arrives (via `PositionedWriter`). This is what the "ordered writer or index-based reassembly" instruction in PROMPT Phase 4 asks for.
17. **No `docs/PROGRESS.md` and no `ARCHITECTURE.md` existed before this file.** Both required by PROMPT deliverables.
18. **`TestHttpServer` payload is an in-memory `ByteArray`** — cannot test the 100 MB / 1 GB scenarios from PROMPT TEST STRATEGY §A without blowing the 1 GiB test heap. Needs a disk-backed / generator-backed source.
19. **TestHttpServer fault coverage is missing** (per PROMPT TEST STRATEGY):
    - HEAD returning 405 is covered (`headAllowed=false`).
    - HEAD "lying" about `Content-Length` is not covered.
    - Random connection resets mid-segment is partially covered (`cutFirstBodies` / `earlyCloseAfterBytes`).
    - Slow-start / no-bytes-for-N-seconds: covered by `hangFirstBodies`.
    - **429 / 503 responses with and without `Retry-After`**: not covered.
    - **ETag/Last-Modified changing between pause and resume**: `validator` is fixed; not switchable mid-run.
    - **Per-IP connection limit / reject connection N+1**: not covered.
    - **Redirect chains, `Content-Disposition`, gzip content-encoding**: not covered.
    - **Multi-range-safe behavior**: not covered.
    - **Truncated responses (fewer bytes than Content-Length)**: partially covered by `earlyCloseAfterBytes` (drops connection), but not "clean truncation" (server closes with 200 but body shorter).
    - **No SHA-256 helper on the test side** — every test uses `assertBytes` doing elementwise comparison; fine for small files, too slow for 100 MB+.
20. **No HLS fixtures on the harness side** — `HlsDownloaderIntegrationTest` exists (278 lines) and appears to construct in-memory playlists, but per PROMPT we still need AES-128 key rotation, fMP4 (`EXT-X-MAP` / `EXT-X-BYTERANGE`), and simulated live coverage.
21. **No local torrent seeder harness** — `TorrentSwarmBenchmark.kt` (138 lines) exists but is a benchmark, not correctness. `TorrentDownloaderTest`, `TorrentPauseResumeTest`, etc. exercise small fixtures but not restart-with-fastresume.
22. **`BandwidthLimiter` is one global bucket**, correctly shared. But **there is no per-download limit**, no "smart" priority weighting inside the bucket, and no per-host limit.
23. **`DownloadManager.fetchMetadata`** runs an entire executor for 30 s to *preview* a torrent. This actually starts a temporary `BtClient` on the shared runtime for a few seconds and tears it down. Works but is heavyweight; also, `getCacheKey(request)` is only ever consulted inside `cacheMetadata` from the real executor, not this preview path. Preview and real download use *different* cache keys (`id` vs `sha256(url)`), so the preview cache file is not the one the real download reads. Minor waste.

### 16. Existing test coverage

Test source set has **49 test files**, ~4 000 LOC, covering:
- HTTP: segmented transfer, resume transitions, stalled workers, connection reuse, failure injection, parallelism, range-support reporting, conflict rename, fairness across downloads, tail latency, transfer planner, completed chunks, retry classification, timeout retry, scheduler pause, queue consistency, download manager e2e, HLS+archive persistence, portable resume, speed-limit binding.
- Archive: remote-zip parse + HTTP integration.
- HLS: parser unit + downloader integration.
- Torrent: downloader, metadata, pause/resume, url source, performance config.
- Storage: positioned writer, torrent content layout/session.
- Benchmarks: HTTP concurrency scaling, connection latency, tail latency, throughput, torrent swarm. Skipped by default, run via `./gradlew :download-engine:httpBenchmark`.

Overall test quality is high; the HTTP path is well pinned. **Correctness coverage for HLS is thin** relative to Phase 4, and **torrent correctness coverage** is functional but doesn't test the resume/rehash performance behaviour.

### 17. Prioritized findings — impact × effort

Impact is the expected throughput / reliability / correctness improvement. Effort is code + test work.

Legend: ⭐ = must-do, 🔹 = worth doing, 💤 = defer.

| # | Finding | Impact | Effort | Priority |
|---|---|---|---|---|
| 1 | HLS truncated-segment silent-corruption bug (15.3, 15.19) | **Correctness** – can produce a broken output file | M | ⭐ |
| 2 | `Retry-After` ignored for 429/503 (15.1) | Reliability, ban-avoidance | S | ⭐ |
| 3 | No chunk-level backoff / jitter (15.2) | Reliability, ban-avoidance | S | ⭐ |
| 4 | Test harness: extend `TestHttpServer` + add SHA-256 + 100 MB/1 GB support (15.19) | Enables every measurement after | M | ⭐ |
| 5 | Baseline benchmark + `PROGRESS.md` numbers per protocol | Enables Phase 3–5 measurement | S | ⭐ |
| 6 | HLS write-once assembly via `PositionedWriter` at absolute offsets (15.16) | Removes 2× disk write, enables streaming output | M | ⭐ |
| 7 | HLS parallel segment concurrency configurable (currently hard-coded 4) | Throughput on high-latency CDNs | S | ⭐ |
| 8 | Torrent: fast resume / sampled hashing (15.6) | — | — | ❌ NOT POSSIBLE: no such API in `bt` 1.10 (see §11) |
| 9 | Torrent: enable UDP trackers (15.7) | Peer discovery on udp-only torrents | S | ⭐ DONE — wired from `bt-core`, **no dep added** |
| 10 | Torrent: reduce `maxOutstandingRequests` (15.5) | Peer compatibility (unmeasurable locally) | — | ⏸ Kept at library default; harness cannot exercise it |
| 11 | Per-host connection budget across concurrent downloads | Bandwidth fairness, ban-avoidance | M | 🔹 |
| 12 | Adaptive connection count (back off on 429/403/503) | Reliability on throttling hosts | M | 🔹 |
| 13 | Cookie / auth session support | Enables login-gated direct downloads | S | 🔹 |
| 14 | Buffer pool on HTTP hot path | Small GC pressure reduction | S | 🔹 |
| 15 | Decouple disk writes with a bounded `Channel<ByteArray>` per worker | Isolate disk stalls from socket stalls; more risk than reward on a fast local disk, so gate behind a flag and a benchmark | M | 🔹 |
| 16 | `If-Range` on resume, avoiding byte discard when validator mismatches | Save bytes on rare host rotation | S | 🔹 |
| 17 | Optional SHA-256 checksum verification of the completed file | User-facing correctness | M | 🔹 |
| 18 | Dynamic segment re-planning (split largest remaining on worker completion) | Tail-latency on very skewed link | L | 💤 |
| 19 | Mirror / multi-source | Only helps a niche user set | L | 💤 |
| 20 | Live HLS support | Big architectural change | L | 💤 |
| 21 | fMP4 (`EXT-X-MAP`/`EXT-X-BYTERANGE`) HLS | Big architectural change | L | 💤 |
| 22 | ffmpeg remux integration | Nice-to-have | L | 💤 |
| 23 | HTTP/2 multiplexed segmented path — measure vs multiple HTTP/1.1 keep-alives | Empirical; likely HTTP/1.1 pool wins on bulk | M | 🔹 (measure first) |
| 24 | `download-service` layer currently reads `DownloadSettings.logLevel` and re-inits SLF4J. Not our module, but flag it. | — | — | 💤 |

**Not fixing / leaving alone (honesty)**:

- µTP and web seeds: `bt` 1.10 does not implement µTP and has **no `WebSeed` class** (an earlier note claiming a `WebSeedService` was wrong). Nothing to wire; stated plainly.
- Fast resume / sampled hashing: no API exists in `bt` 1.10; the restart re-hash cost is inherent to this library version.
- Super seeding: `bt` does not provide it.
- LSD, MSE, UDP trackers: these turned out to be **available in `bt-core`** and are now wired (Phase 5), reversing the original "not supported" note.

### 18. Assumptions and open questions for review

Per ground rule 8, flagging:

- **A1**: ~~adding `bt-upnp-tracker-client` counts as a small dependency add~~ — **moot.** That artifact does not exist; Phase 5 wired UDP trackers from `bt-core`, so **no dependency was added** and the "ask" gate never triggered.
- **A2**: I assume it is safe to **change the persisted HTTP resume sidecar format** if needed, provided we keep a compatibility reader. Rule says "changing persisted-state formats" → ask before doing it. Currently no format change is planned.
- **A3**: ~~leave `maxOutstandingRequests=250` to a local-seeder benchmark~~ — **cannot be satisfied.** The localhost `bt`↔`bt` swarm does not complete a transfer in this environment, and would not reproduce third-party strictness even if it did. `250` is also the library default, so it is left unchanged rather than tuned on an unmeasurable guess.
- **A4**: I assume 1 GB HTTP benchmarks against the local harness are acceptable if we (a) stream the payload from a temp file rather than memory, (b) keep the tests fast by using 100 MB.
- **A5**: For the "decouple disk writes" step, I assume we keep the current direct write by default and only add the queue when a benchmark shows a stall.
- **A6**: I will keep public API stable. New fields will be added only as default-valued `data class` parameters or as opt-in settings.

### 19. Implementation plan (Phase 2 → Phase 6 order)

Sequenced to minimize risk and maximize measurement coverage:

**Phase 2 — Harness + baseline (start of next turn if you give the go-ahead).**
1. `TestHttpServer` upgrade: file-backed generator with SHA-256, `Content-Disposition`, gzip encoding option, redirect chains, 429/503 with `Retry-After`, ETag mutator, per-IP conn limit, multi-range-safe behavior, clean-truncated body (server returns fewer bytes but 200 and closes), configurable HEAD lying.
2. `Sha256Sink` / expected-hash helper for byte-exact verification at 100 MB+.
3. New `BenchmarkRunner` (JUnit `@Test` skipped unless `blaze.benchmark=true`) with warm-up + median + spread for total time, MiB/s, TTFB, GC pauses (via `GarbageCollectorMXBean`), CPU % (`com.sun.management.OperatingSystemMXBean`), thread count, connection count from `TestHttpServer`.
4. Fixtures: 10 MiB / 100 MiB / 1 GiB HTTP; local torrent with `bt` seeder; HLS master + variant with 30–40 TS segments and a couple of AES-128-encrypted variants.
5. **Baseline**: capture current-engine numbers for 1/4/8/16/32 connections against a throttled host (say 4 MiB/s per connection) and against an unthrottled localhost.

**Phase 3 — HTTP engine.**
Order (highest impact + lowest risk first):
1. Fix (a) chunk-level retry backoff + jitter, (b) `Retry-After` parsing, (c) cookie plugin install.
2. Reuse `ByteArray` buffers via a tiny object pool inside `HttpNetworkClient` (`ArrayPool<ByteArray>`-like, keyed by size).
3. Per-host connection budget (`Semaphore` keyed on host) shared across concurrent downloads.
4. Adaptive connection count: start at configured, halve on repeated 429/503 (with hysteresis).
5. Benchmarks: Ktor engines (CIO / Java / OkHttp) at 4/8/16/32 conns, localhost vs throttled. OkHttp requires adding a dep; ask.
6. Optional: `If-Range` on resume probe.
7. Optional bounded write queue behind a flag; benchmark on a deliberately throttled disk.
8. Optional checksum verification when the server sends `X-Checksum`/`Content-MD5` or the user supplies one.

**Phase 4 — HLS.**
1. Fix silent-corruption bug: write segments through `PositionedWriter` **at absolute byte offsets in the destination partial**, and track expected segment sizes in a sidecar.
2. Configurable segment concurrency.
3. Add backoff between segment attempts.
4. Parse `EXT-X-MAP`, `EXT-X-BYTERANGE`, `EXT-X-MEDIA`; refuse or handle as appropriate.
5. Optionally enable fMP4 assembly or (better) delegate to ffmpeg if present.
6. Extend tests with out-of-order segments, delays, failures to prove ordering.

**Phase 5 — Torrent.** (Completed; scope re-derived after verifying `bt-core-1.10-sources.jar`.)
1. ~~Enable `HashingStrategy.SAMPLED` for fast resume.~~ — impossible: no such API in 1.10.
2. ~~Add `bt-upnp-tracker-client` (needs user OK).~~ — no such artifact; UDP trackers wired from `bt-core` via new `UdpTrackerModule`, **no dependency added**.
3. ~~Tune `maxOutstandingRequests` down to 100; benchmark.~~ — kept at library default; localhost harness cannot exercise strict-peer behaviour (swarm transfer 0 bytes/90s).
4. ~~Enable web seeds.~~ — `bt` has no web-seed support.
5. Enable MSE (`PREFER_ENCRYPTED`) and Local Service Discovery (`LocalServiceDiscoveryModule`) — both from `bt-core`, done.
6. Update `logTrackers` to treat `udp://` as usable; add `UdpTrackerModuleTest` (offline) proving the wiring.
7. Honest doc: correct §11/§15/§17 to record which `bt` capabilities are real vs fabricated.

**Phase 6 — Unified architecture & quality.**
1. `ARCHITECTURE.md`, updated `PROGRESS.md`.
2. Ensure every protocol emits the same `DownloadState` transitions (add `Merging`, `Verifying` for HLS where they matter).
3. Structured-concurrency review pass.
4. Public-API KDoc.

### 20. What has *not* changed and why

- **No code was modified in Phase 1.**
- The existing engine choice (Ktor `Java` client) is correct and documented; I'm not touching it without a benchmark saying otherwise.
- The resume sidecar format is preserved.
- `HttpDownloadCoordinator` is already the most-tuned component in the module; its segmentation, watchdog, work-requeue, and portable checkpointing are all sound. Improvements there will be incremental (backoff, buffer pool, If-Range), not structural.

### 21. Next step

**Stop and wait for review.** When you approve Phase 1, the first thing I will do is:

1. Cut a branch (`feat/engine-optimization` or one you prefer).
2. Land the extended `TestHttpServer` + fixtures + benchmark runner (Phase 2.1–2.4).
3. Run the baseline benchmark and report numbers.
4. Then propose the exact commit series for Phase 3.

Two things I need explicit approval on:
- Adding `com.github.atomashpolskiy:bt-upnp-tracker-client` (Phase 5). Same `bt` project, no new transitive deps beyond `bt-core`.
- Adding `ktor-client-okhttp` to the classpath as a *benchmark-only* dep so Phase 3.5 can measure it, and — if it wins — promoting it to a runtime dep. Alternative: measure CIO vs Java only.

Everything else stays inside the current dependency set.

---

## Phase 2 – Test harness and baseline

Status: **complete, awaiting review**. Branch `feat/engine-optimization`, five commits landed.

### 1. Test suite went green on Windows

Baseline on this dev machine before Phase 2 started: `:download-engine:test` reported **6 failures
across 188 tests**, all Windows-only:

- 5 in `TorrentContentLayoutTest` — hard-coded `/home/user/Downloads/...` roots assumed the OS
  separator was `/`. Root cause turned out to be a **real production bug** in
  `TorrentContentStorage.torrentContentPath`: the metainfo's own path separator is `/` by bencode
  spec, but the normaliser was splitting elements on `root.fileSystem.separator` — so on Windows a
  hostile element like `/etc/passwd` stayed intact and either escaped the destination folder or
  failed the containment check. Fix (commit `75ec1bf`): pre-split every element on `/` before
  normalising, re-join on the OS-native separator. The layout tests were rewritten on
  `Files.createTempDirectory` roots with `/`-joined relativised strings for the documented name-
  normalisation table, so the same contract is asserted identically on every OS.
- 1 in `DownloadManagerTest > test remove http download with no files on disk is a clean no-op` —
  did not reproduce on any subsequent clean run of the full suite; treated as an unrelated
  environmental flake in the pre-fix baseline and left alone. If it resurfaces we will bisect.

Post-fix: **188 tests, 0 failures** locally on Windows.

### 2. Extended `TestHttpServer` (commits `c4b864d`, `0a567da`)

New post-construction fault knobs, all `@Volatile var`, defaults preserve every existing test call
site:

| Knob | Purpose |
|---|---|
| `failStatus` / `failFirstN` / `retryAfter` | Inject 429 or 503 with an optional `Retry-After` on the first N *data-plane* requests. HEAD and the 1-byte probe are deliberately spared so a test of retry semantics cannot be confused by a broken probe. |
| `etagSwapAfterRequests` / `etagAfterSwap` | Flip the `ETag` mid-transfer once a request threshold is crossed — the case `If-Range` needs to catch. |
| `redirects: Map<String, Redirect>` | Per-path 3xx with configurable status and `Location`. |
| `contentDisposition` | Emit `Content-Disposition` on 200/206. |
| `headContentLengthLie` | HEAD returns a `Content-Length` that disagrees with the GET. |
| `maxConcurrentConnections` | Accept-then-close sockets beyond the cap; models the CDN per-IP limit. |
| `gzipNonRanged` | Ignore `Accept-Encoding: identity` and gzip the whole-body 200 anyway, with the correct `Content-Encoding` and recomputed `Content-Length`. |
| `cleanTruncateBodies` | Advertise the full `Content-Length`, stream ~⅔ of the bytes, then close the socket with a graceful EOF and **no exception** — the case a naive client reads as success. |
| `multiRange: WholeBody / Multipart / Reject` | Three RFC 7233 answers to `Range: bytes=a-b,c-d`. Default `WholeBody` matches most CDN fallback behaviour. |

`TestHttpServerFaultKnobsTest` (12 tests, all green) pins the wire behaviour of every new knob using
a **raw-socket HTTP/1.1 client**, not `HttpURLConnection`, because `HttpURLConnection` throws on
the `cleanTruncateBodies` case and would hide the very bug the knob exists to expose.

### 3. File-backed `PayloadGenerator` (commit `c4b864d`)

`PayloadGenerator.writeTo(path, size, seed)` streams the same LCG as `TestHttpServer.of` (byte-
identical, verified by `PayloadGeneratorTest.generated file matches the in-memory LCG byte-for-byte`)
but never materialises the whole array, so a 100 MiB / 1 GiB benchmark can produce a real file for
modest RAM. `sha256OfGenerated(size, seed)` hashes the would-be stream without writing anything,
which gives every later correctness test a single assertion:
`sha256File(destination) == sha256OfGenerated(size, seed)`. That catches every class of segmentation,
resume and reorder bug in one check.

### 4. `BenchmarkRunner` (commit `HttpBaselineBenchmark`, pending)

Location: `download-engine/src/test/kotlin/org/blaze/engine/benchmark/BenchmarkRunner.kt`. Runs
`warmups` discarded iterations then `samples` measured ones. Reports median / min / max wall time,
median throughput, GC pause time and collection count (delta across `GarbageCollectorMXBean`s),
peak used heap, peak live thread count, TTFB where the caller marks it, plus a `Map<String, Any>`
of benchmark-specific extras (used here for server-side bytes served and connections opened).

### 5. Baseline HTTP benchmark

`HttpBaselineBenchmark` (skipped unless `-Dblaze.benchmark=true`, run via
`:download-engine:httpBenchmark`). 32 MiB LCG payload, keep-alive server, 1 warm-up + 3 samples
per config, throttled and unthrottled scenarios.

```
=== HTTP baseline (payload=32 MiB, warmups=1, samples=3) ===

  unthrottled conn=1  : 141.4 MiB/s median, wall 194.7/226.3/390.0 ms, gc 5.0/1, heap 131 MiB, threads 25
  throttled 4 MiB/s conn=1  : 4.00 MiB/s median, wall 8007/8008/8009 ms,  gc 11.0/2, heap 155 MiB, threads 28

  unthrottled conn=4  :  78.1 MiB/s median, wall 332.0/409.7/428.8 ms, gc  3.0/1, heap 163 MiB, threads 52
  throttled 4 MiB/s conn=4  :  14.4 MiB/s median, wall 2217/2228/2245 ms,  gc  7.0/2, heap 148 MiB, threads 67

  unthrottled conn=8  :  85.6 MiB/s median, wall 329.4/373.9/385.5 ms, gc  5.0/1, heap 157 MiB, threads 102
  throttled 4 MiB/s conn=8  :  25.5 MiB/s median, wall 1222/1255/1263 ms,  gc  9.0/2, heap 151 MiB, threads 132

  unthrottled conn=16 : 105.5 MiB/s median, wall 299.3/303.2/345.6 ms, gc  5.0/1, heap 163 MiB, threads 213
  throttled 4 MiB/s conn=16 :  40.6 MiB/s median, wall  743/ 788/ 814 ms,  gc  9.0/2, heap 166 MiB, threads 277
```

Readings:

- **Unthrottled loopback, more connections are strictly worse at 4 and 8, only marginally better
  at 16.** The single-stream path is the fastest thing on the machine, and every extra worker is
  paying for chunk coordination, atomic bookkeeping and per-worker buffer allocation on data that
  is essentially free to fetch. This is the empirical form of Phase-1 findings §7 (per-buffer
  overhead) and §11 (§11 not re-checked here; the numbers just say the segmented hot path has
  software cost that the network does not hide). A Phase-3 win has to *reduce that overhead*, not
  add more connections.
- **Throttled scaling is close to linear at 4 and 8, starts to saturate at 16.**
  `conn=4` reaches 3.6× ideal, `conn=8` reaches 3.2×, `conn=16` reaches 2.5×. The tail latency on
  the last chunk dominates as the worker count grows — the classic "one slow worker holds the
  download open" problem that work-stealing (Phase 3) is supposed to fix. Numbers here are the
  exact "before" Phase 3 needs to beat.
- **GC is essentially free** — 3–11 ms across 1–2 collections for the whole download, regardless
  of connection count. That matches Phase 1's §7 estimate: 128 KiB buffers amortise well.
- **Thread count is super-linear in `connections`**: 25 → 52 → 102 → 213 as we double from 1 → 4
  → 8 → 16. That is ~13 threads per connection, more than the `connections` workers themselves.
  The extras come from the JDK HttpClient's internal executor and Ktor's IO dispatchers. Worth
  noting for Phase 3's "adaptive connection count" upper bound — going to 32 is going to be
  ~400+ threads before the ceiling even bites.

### 6. What was *deliberately* deferred

PROMPT.md Phase 2 also asks for HLS and torrent fixtures beyond what the current tests use, and
baseline numbers for those two paths.

- **HLS.** The existing `HlsDownloaderIntegrationTest` already covers VOD master, single-variant,
  AES-128 (with sequence-derived IVs), SAMPLE-AES consent gate, live rejection, progress-callback
  byte totals, and inspect-does-not-fetch-segments. What is *not* covered and Phase 4 will need:
  fMP4 (`EXT-X-MAP` + `EXT-X-BYTERANGE`), `EXT-X-MEDIA` alternate audio/subtitle renditions, key
  rotation, out-of-order segment delivery with ordered reassembly, a simulated live playlist that
  appends segments. Rather than build those fixtures speculatively, Phase 4 will add each as it
  is implemented — a fixture without a matching behaviour is dead weight in the review.
- **Torrent.** The existing `TorrentDownloaderTest`, `TorrentPauseResumeTest`, `TorrentUrlSourceTest`
  and `TorrentMetadataTest` already exercise the local-seeder path (bt `BtClient` bound to
  localhost). What is missing for Phase 5 is a fast-resume restart test and dead-tracker
  behaviour; those go in the same phase that changes the behaviour.
- **Baseline HTTP throughput for alternative Ktor engines** (CIO vs Java vs OkHttp) is *the* thing
  Phase 3.5 needs to decide which engine to keep, but OkHttp is not yet on the classpath (the
  user's answer on that question was yes-benchmark-but-only-as-test-dep; I'll add it as part of
  Phase 3.5's commit series, not Phase 2).

### 7. Files added or touched (Phase 2)

- `download-engine/src/main/kotlin/org/blaze/engine/storage/TorrentContentStorage.kt` — bug fix.
- `download-engine/src/test/kotlin/org/blaze/engine/storage/TorrentContentLayoutTest.kt` — rewritten
  cross-platform.
- `download-engine/src/test/kotlin/org/blaze/engine/support/TestHttpServer.kt` — fault-injection
  knobs + `isMultiRange` / `cleanTruncateBodies` / redirect / gzip / per-IP-cap code paths.
- `download-engine/src/test/kotlin/org/blaze/engine/support/PayloadGenerator.kt` *(new)*.
- `download-engine/src/test/kotlin/org/blaze/engine/support/PayloadGeneratorTest.kt` *(new)*.
- `download-engine/src/test/kotlin/org/blaze/engine/support/TestHttpServerFaultKnobsTest.kt` *(new)*.
- `download-engine/src/test/kotlin/org/blaze/engine/benchmark/BenchmarkRunner.kt` *(new)*.
- `download-engine/src/test/kotlin/org/blaze/engine/benchmark/HttpBaselineBenchmark.kt` *(new)*.

### 8. Next step

**Stop and wait for review.** When you approve Phase 2 I will start Phase 3 in this order:

1. Engine-choice benchmark (Java vs CIO vs OkHttp) so the rest of Phase 3 is measured against the
   winner. Requires adding `ktor-client-okhttp` to `testImplementation` only.
2. Correctness gaps first, before throughput wins: `Retry-After` handling, `If-Range` validation
   against ETag swap, chunk-level exponential backoff with jitter, redirect handling re-probing
   against the new URL, HEAD-vs-GET disagreement handling.
3. Hot-path throughput: work-stealing on the largest remaining chunk, adaptive connection count
   that backs off when 429/503 starts appearing, per-worker buffer pool (drop the per-chunk
   `ByteArray` allocation), bounded disk-write queue to decouple network from disk.
4. Speed-limiter review (leaky bucket is fine today but the per-128 KiB `synchronized` is worth
   measuring against a striped variant).

---

## Phase 3 – HTTP engine

Status: **in progress**. Branch `feat/engine-optimization`.

### 1. Engine choice: Java vs CIO vs OkHttp

`HttpEngineBenchmark` measures the same 32 MiB LCG payload, the same `{1,4,8,16}` connection
ladder and the same unthrottled / 4 MiB-s-per-conn scenarios against the three Ktor engines, with
identical timeout / redirect configuration across all three. Numbers below are median-of-3 after
1 warm-up.

```
=== Ktor engine comparison (payload=32 MiB, warmups=1, samples=3) ===

engine      | unthrottled conn=1 / 4 / 8 / 16   | throttled-4MiBps conn=1 / 4 / 8 / 16
------------+-----------------------------------+------------------------------------------
Java        | 146.6  /  83.1  /  94.1  / 106.3  |   4.00 / 14.39 / 26.11 / 42.57 MiB/s
CIO         | 258.86 /  81.98 /  91.89 /  90.52 |   4.00 / 14.19 / 26.17 / 42.72 MiB/s
OkHttp      | 301.45 / 105.86 / 111.77 / 110.70 |   4.00 / 14.41 / 26.32 / 44.16 MiB/s

Resources (conn=16 unless stated):
  Java   : 214 threads (unthr) / 278 threads (thr), 170 MiB heap, 4-10 ms GC
  CIO    : 53 threads, 117-185 MiB heap, 0-2 ms GC
           BUT serverConnections = 34 at conn=4/8/16 - the KDoc warning is real:
           CIO opens a fresh TCP connection for every request, the pool is not reused.
  OkHttp : 69 threads (unthr) / 75 threads (thr), 93-187 MiB heap, 0-4 ms GC
           serverConnections matches requested connections exactly (1 / 4 / 7-8 / 10-18),
           so the pool actually pools.
```

Readings:

- **OkHttp wins unthrottled throughput on every connection count.** 2.0-2.1× Java at conn=1
  (301 vs 147 MiB/s), 1.27-1.3× Java at conn=4 (106 vs 83) and 8 (112 vs 94), comparable at
  conn=16 (111 vs 106). Under the 4 MiB/s/conn cap it matches Java at conn=1/4 and beats Java by
  1-4% at conn=8/16. There is no scenario in this recipe where Java beats OkHttp.
- **CIO is disqualified by connection reuse**, not by throughput. `serverConnections = 34` at
  conn=4 is the smoking gun: it opens a fresh socket per request. On loopback the handshake cost
  is hidden by the very high effective bandwidth (its conn=1 number, 259 MiB/s, is the fastest
  in the set), but on a real host every chunk would pay a full TCP - and TLS - handshake and the
  abandoned sockets pile up in TIME_WAIT, exactly the KDoc reason for not picking CIO in
  `DownloadManager.createDefaultHttpClient`.
- **OkHttp is dramatically lighter on threads.** 69-75 threads at conn=16 vs Java's 214-278.
  That is not just a nice-to-have: the ceiling for `maxConnectionsPerDownload` scaling is bound
  by thread count in the current engine, and OkHttp's per-connection thread cost is essentially
  flat. OkHttp's GC pause (0-4 ms across the download) is also less than half Java's 3-11 ms.
- **Heap footprint** is comparable at conn=16 (Java 170 MiB, OkHttp 174 MiB) but OkHttp's
  conn=1 heap is 82 MiB vs Java's 145 MiB - the JDK client's `HttpClientImpl` selector-manager
  keeps more state warm.

Decision: **switch the production client from Ktor Java to Ktor OkHttp in the next Phase-3 commit,
keeping the same timeouts (`requestTimeoutMillis=INFINITE`, `connectTimeoutMillis=15_000`,
`socketTimeoutMillis=60_000`) and `followRedirects = false`.**

Notes on the swap:

- OkHttp's own engine-level timeouts (`OkHttp.create { ... }`) are deliberately *not* configured;
  the Ktor `HttpTimeout` plugin maps onto the underlying client and keeps the numbers identical
  across engines. If a specific timeout does not translate we will see it in the test suite and
  add an engine-specific override, not a change to the shape of the timeout policy.
- OkHttp 4.x brings `okio` and `kotlin-stdlib-jdk8` as transitives; already present via Ktor.
- `HttpNetworkClient` currently sets `Accept-Encoding: identity` on the socket. OkHttp by default
  transparently decompresses gzip responses. Because we ask for `identity`, that path should be
  inert; a `TestHttpServer` fixture (`gzipNonRanged`) exists to prove the behaviour end-to-end
  after the swap.

### 1b. Post-swap baseline (same recipe, OkHttp now the production engine)

`HttpBaselineBenchmark` re-run against the new default client, same payload, same connection
ladder, same cap. `Phase 2 §5` is the "before" table.

```
           | unthrottled MiB/s  Java -> OkHttp        | throttled 4 MiB/s/conn Java -> OkHttp
-----------+-----------------------------------------+----------------------------------------
 conn=1    | 141.4  -> 191.4   (+35%)                |  4.00 ->  4.00   (unchanged)
 conn=4    |  78.1  ->  86.6   (+11%)                | 14.40 -> 14.31   (-0.6%, noise)
 conn=8    |  85.6  -> 103.8   (+21%)                | 25.50 -> 26.04   (+2%)
 conn=16   | 105.5  -> 103.7   (-2%,  noise)         | 40.60 -> 44.47   (+10%)

peak threads @ conn=1/4/8/16 : 27 / 42 / 47 / 61  (was 25 / 52 / 102 / 213)
peak heap    @ conn=1/4/8/16 : 79 / 132 / 137 / 169 MiB (was 131 / 163 / 157 / 163 MiB)
```

Interpretation:

- Unthrottled single-stream is 1.35x Java; that is the client-side overhead the Phase 2 baseline
  flagged as "software cost the loopback network cannot hide", and OkHttp just cut it in the way
  its own connection dispatch does.
- Throttled scaling at conn=16 improves 10%, which is where the tail-latency story was weakest.
- **Thread footprint is the biggest win.** At conn=16 we went from 213 live threads to 61. That
  changes the ceiling for `maxConnectionsPerDownload` from "about 16 before we're running
  hundreds of threads per active download" to "room to grow", which the Phase 3 adaptive-conn
  work needs.
- Heap is comparable at conn=16 but roughly 40% lower at conn=1.
- The full 203-test suite passes against the new engine (one transient timing flake in
  `TimeoutRetryTest` did not reproduce on `--rerun-tasks` after a clean build; that test is
  independent of the production client - it constructs its own CIO client).


### 2. Correctness gaps

Order:
1. ~~Engine choice~~ (done, §1/§1b).
2. ~~`Retry-After` handling~~ (done, §2a).
3. ~~Chunk-level exponential backoff with jitter~~ (done, §2a).
4. ~~`If-Range` validation on resume against a swapped ETag~~ (done, §2b). Redirect re-probe against
   the new URL was already correct: `HttpNetworkClient.probe`/`stream` resolve a 3xx and replay the
   `Range` against the new Location without leaking auth headers cross-origin (see Phase-1 §15.13).
5. HEAD-vs-GET disagreement (already test-covered; verify the engine handles it after the swap).
   (done — `probe` trusts a proven ranged-GET `206` over a lying HEAD via `maxOf`, §25/§1b; no change)

#### 2b. `If-Range` on the segmented data path (this commit)

Closes the Phase-1 §15.9 finding. The resume-at-start case was already safe (the probe's validator is
compared against the sidecar before any byte is trusted), but the *mid-transfer* case was not: a
segmented run issued every chunk `Range` request without an `If-Range` validator, so if the host
rotated the representation while workers were fetching, some chunks came from the old file and some
from the new - stitched together into a **silently corrupt output that still reported success**. No
corruption existed on a clean pause/resume; it existed only for a rotation *during* a run.

**The mechanism (RFC 9110 §14.22).** A ranged GET that carries `If-Range: <validator>` gets a `206`
for the range when the validator still matches, but a **whole-body `200`** when it no longer does.
Because the segmented path is only reached after the probe has *proven* ranged reads (a real `206` on
`bytes=0-0`), a `200`-to-a-ranged-request during a segmented run is unambiguous: the representation
changed under us. The `If-Range` presence is itself the discriminator that separates "the file
rotated" from "this host never supported ranges" (the latter only happens with no validator to send).

**Implementation:**
- `HttpNetworkClient.stream`/`streamAt` gained an optional `ifRange: String?` (added *before* the
  `sink` parameter so the trailing-lambda call sites stay source-compatible) that emits
  `If-Range` alongside `Range`. Only sent on a ranged request.
- `HttpDownloadCoordinator.segmented` now threads the transfer's `probe.validator` into every chunk
  request as `ifRange`, and the worker's `RangeIgnored` branch splits on whether a validator was
  sent: with one, an `AtomicBoolean rotated` is raised (plus a retryable error to stop the siblings);
  without one, the old fatal `RangeUnsupported` is kept.
- The segmented driver was refactored into an outer `segmented` loop plus `segmentedOnce`. On a
  rotation `segmentedOnce` closes the writer, **deletes the mixed partial and its sidecar**, fires
  the genuine-restart advisory, and asks for a fresh probe; the wrapper re-probes and re-runs the
  pool against the new validator, bounded to `MAX_ROTATION_RESTARTS = 1`. A second consecutive
  rotation surfaces a retryable `NetworkFailure` rather than looping. The recovery happens inside a
  single `download()` call, so it does not depend on the task-level `autoRetryFailed` setting.

**Verification (`IfRangeTest`, 4 tests, all green):**
- Two wire-level tests on the primitive: a *matching* `If-Range` still yields a `206` and the full
  range streams through (and the header demonstrably reaches the server); a *stale* `If-Range` makes
  the harness answer a whole-body `200`, which the client surfaces as `RangeIgnored`.
- The end-to-end test drives an 8-chunk / 4-connection segmented transfer against a host whose ETag
  rotates after the probe (`etagSwapAfterRequests = 2`): the download still returns `Success`, the
  file is byte-exact, the mismatch path was taken (`ifRangeMismatchServed >= 1`), a restart was
  reported, and the re-fetch carried the new validator - proving the mixed bytes were discarded and
  re-fetched cleanly rather than finalised corrupt.
- A healthy-host control run never downgrades a range and never restarts, so the guard is inert on
  the fast path.
- Full `:download-engine:test` suite passes at **223 tests, 0 failures** (the 4 new on top of the 219
  after the adaptive-connection commit); the `segmented` refactor and the `stream` signature change
  broke no existing transfer/resume/failure test.

#### 2a. `Retry-After` + chunk-level backoff (this commit)

Two reliability bugs from §15.1/§15.2 are now closed. A throttling host that answers `429`/`503`
with a `Retry-After` is honoured instead of hammered, and a flaky host that sends *no* hint still
gets spaced-out chunk retries instead of a back-to-back burst.

**`Retry-After` parse (`HttpNetworkClient`)**
- `StreamResult.Failed` gained `retryAfterMillis: Long?`. On a non-success status the client now
  calls `parseRetryAfter(headers, status)` and attaches the result.
- `parseRetryAfter` accepts both RFC 9110 forms - delta-seconds (`Retry-After: 120`) and HTTP-date
  (IMF-fixdate via `DateTimeFormatter.RFC_1123_DATE_TIME`) - but only on `429` and `503`; a stray
  header on any other status is ignored. Negative counts and unparseable values fall through to
  "no hint". The result is clamped to `RETRY_AFTER_CEILING_MILLIS = 15 min` so a hostile or
  misconfigured `Retry-After: 999999999` cannot park a chunk forever.

**Threading the hint through the data path**
- `HttpDownloadCoordinator`: `Outcome.Failure` and `StreamOutcome.Failed` carry `retryAfterMillis`.
  The segmented path tracks `lastRetryHintMillis: AtomicLong(-1)` so the final `Outcome.Failure`
  surfaces the last-seen hint even after the per-chunk budget is exhausted.
- The single-stream path returns the hint directly on `Failure`.
- `DownloadExecutorImpl`: a `pendingRetryHintMillis` scratch field captures the hint from
  `Outcome.Failure` and the task-level scheduler takes `maxOf(policyDelay, serverHint)` for the
  next attempt's wait - the server's floor wins when it is longer than our own backoff.

**Chunk-level exponential backoff (`HttpDownloadCoordinator.worker` → `requeue`)**
- Previously a failed chunk was re-queued with **zero delay**; four workers meant four back-to-back
  retries. Now `requeue` computes `chunkBackoffMillis(attempt, retryAfter)` and stamps
  `readyAt[chunk]`; `claim` became `suspend` and `delay`s until the chunk's head-of-retry time, so
  the worker actually waits instead of immediately re-requesting.
- The schedule is **equal-jitter exponential**: base `CHUNK_BACKOFF_BASE_MS = 1000`, cap
  `CHUNK_BACKOFF_CAP_MS = 30000`, window `base << (attempt-1)`, delay `= half + rand(half)`. So the
  first four attempts span `[500,1000) / [1000,2000) / [2000,4000) / [4000,8000) ms`. Equal-jitter
  (rather than full `[0, window]` jitter) keeps a hard floor per attempt so the backoff can never
  collapse to an instant retry, while still decorrelating a herd; a server hint short-circuits the
  schedule entirely (`chunkBackoffMillis` returns the hint verbatim).

**Verification (`RetryAfterAndBackoffTest`, 6 tests, all green)**
- 3 pure-parser tests pin the delta-seconds, HTTP-date, ceiling-clamp and ignore cases directly so
  they survive any change to the fault-injection knobs.
- 3 end-to-end tests use the extended `TestHttpServer` (`failStatus`/`failFirstN`/`retryAfter`)
  across `connections = 4` to exercise the segmented path: a `429` with `Retry-After: 2` cannot
  finish under 2 s (measured ~2.12 s), an unhinted flaky `500` cannot finish under 700 ms (measured
  ~1.4-1.5 s across three re-runs - deterministic, comfortably above the floor), and an always-`503`
  run that exhausts the chunk budget still surfaces `retryAfterMillis == 2000` on `Outcome.Failure`.
- Full `:download-engine:test` suite (209 tests incl. the 6 new) passes; no regressions from the
  `worker`/`requeue`/`claim`/`Outcome` signature changes.

### 3. Hot-path throughput — measured before touching the data path

Per the ground rules (measure first, don't add coordination risk for an unproven gain), the tail
and concurrency benchmarks were re-run on the post-swap OkHttp engine before any change. Three
runs each.

**`HttpTailLatencyBenchmark` (throttled — the case segmentation exists for):**
- Uniform cap, 32 MiB @ 8 MiB/s/conn, 4 conn: ideal 1.00 s, actual **1.22 s = 1.22× ideal, all 4
  workers active.** Residual tail cost is 22%.
- Slow final region (last 8 MiB @ 1 MiB/s/conn): 2 conn **5.73 s**, 4 conn **3.12 s**, one-connection
  serialised reference 8.00 s. Doubling connections cuts the tail ~1.8×, i.e. the slow region is
  genuinely drained in parallel, not funnelled through one worker.

**`HttpConcurrencyScalingBenchmark` (unthrottled loopback, 32 MiB / 32 chunks):**
```
         | Run A  | Run C  | Run D
---------+--------+--------+--------
 conn=1  | 173.3  | 164.0  | 165.1   (single stream)
 conn=2  |  82.4  |  92.9  |  82.5
 conn=4  |  89.4  | 106.1  | 101.9
 conn=8  |  97.4  |  94.9  | 103.7
 conn=16 |  39.7  |  97.0  | 102.9   (Run A dip did NOT reproduce -> GC/disk hiccup, not contention)
```

Findings:
- The `2.5×`-ideal 16-conn "saturation" the Phase-2 plan attributed to a tail imbalance is **noise**:
  it appeared once and was ~1.0× the 8-conn figure on both re-runs. The shared `FileChannel`
  write path (one channel serialising positioned writes across workers) is a *theoretical* cliff,
  not an observed one at these connection counts.
- **Work-stealing / late chunk-splitting is NOT warranted.** The throttled test already bounds the
  tail (1.22× uniform, slow region parallelises 2→4 conn), so there is no measurable imbalance to
  steal away. This is exactly the precondition the benchmark's own doc set before adding splitting:
  "if those units already let several workers drain a slow final region together, late splitting
  buys nothing and would only add coordination risk." Measurement says they do.
- On an *unthrottled* loopback single-stream is the ceiling (~165 MiB/s); segmentation is strictly
  slower there because there is no network to parallelise, only per-chunk request + write overhead.
  This is expected and is not a defect — segmentation earns its keep on per-connection-capped hosts,
  which is what the throttled test models.
- `BandwidthLimiter.acquire` early-returns without taking its monitor when unlimited, so the unthrottled
  sweep never touches the lock; the limiter is not a hot-path cost until a cap is set (§4 stands).

Remaining §3 items and their status after measurement:
1. ~~Work-stealing~~ — **declined on evidence** (no measurable tail bottleneck).
2. ~~Adaptive connection count~~ backed off on repeated 429/503 — **done (§3b)**. Its value is
   reliability/politeness under a throttling host (avoid ban, honour `Retry-After`), not raw
   throughput, and it builds directly on the §2a hint plumbing.
3. Per-worker buffer pool — **no measured win** (allocation/GC is not the limiter: single-stream
   already beats segmented and there is no GC cliff). Low value; only worth it behind the write-queue
   work if that is built.
4. Bounded disk-write queue — the premise (isolate disk stalls from socket stalls) is not observed:
   no reproducible write-path cliff. **Declined on evidence**; will note the negative result so it
   is not re-litigated.

#### 3b. Adaptive connection count (this commit)

The one §3 item that earns its place, and on reliability rather than raw speed: when a host starts
answering `429`/`503`, holding the full connection pool open turns one throttled server into a ban.
The engine now sheds concurrency under throttle and wins it back slowly once the host goes quiet,
building directly on the §2a `Retry-After` plumbing.

**`ConnectionWindow` (new, `execution/ConnectionWindow.kt`)** — a resizable concurrency gate with
hysteresis, wrapped around the segmented worker pool:
- `acquire()` is `suspend`: a worker blocks only while `inFlight > allowed`; on a healthy host
  `allowed == workerCount` so the increment always satisfies `inFlight <= allowed` and returns
  immediately — the gate is one atomic bump on the fast path.
- `noteThrottle()` halves the window, but **at most one reduction per cooldown** (default 2 s):
  when a whole pool observes the same `429` in one instant it must not march `8 -> 4 -> 2 -> 1`.
- `noteSuccess()` grows the window **one step per N consecutive clean chunks** (default 4), never
  snapping back to full width, so recovery is deliberately slower than shed.
- `noteThrottle()` clears the banked successes, and the window is floored at `MIN_WINDOW = 1` so a
  badly throttled host still makes progress instead of stalling.
- The reduce clock is injectable (`clock: () -> Long`) purely so the cooldown/recovery policy is
  testable without sleeping; production uses `System::nanoTime`.

**Signal and wiring:**
- `HttpNetworkClient.StreamResult.Failed` gained `throttled: Boolean`, set when the status is `429`
  or `503`, so the coordinator distinguishes "back off the pool" from an ordinary retryable failure.
- `HttpDownloadCoordinator.segmented()` creates a `ConnectionWindow(max = workerCount)` only when
  `settings.httpAdaptiveConnectionsEnabled` (new, default **true**) and `workerCount > 1`; the
  single-stream and disabled paths pass `null` and are byte-for-byte the old behaviour. Each worker
  brackets its stream in `acquire()` / `finally release()`, calls `noteSuccess()` on `Success` and
  `noteThrottle()` on a `throttled` failure.
- `EngineMetrics` records the low-water mark via the window's `onChange` hook
  (`minConnectionWindow`, surfaced on `DownloadDiagnostics`), which doubles as the test signal.

**Verification:**
- `ConnectionWindowTest` (7 tests, deterministic via a fake nano clock) pins: first throttle halves;
  repeat throttles inside the cooldown collapse to one step; never below 1; growth is one step per
  full clean run; growth caps at max; a throttle clears banked successes; and the gate bounds
  observed concurrency to the window (6 coroutines through a width-3 gate peak at <= 3).
- `AdaptiveConnectionTest` (3 end-to-end tests against the extended `TestHttpServer` returning
  `429` on the first four chunk requests) proves the window actually sheds below the 4-worker pool
  (`minConnectionWindow in 1..3`) **while the file still lands byte-exact**; the same run with the
  feature off, and a healthy-host run with it on, both leave `minConnectionWindow == 0`, so a shed
  is attributable to a throttle and never fires on the fast path.
- **No fast-path regression**: `HttpTailLatencyBenchmark` re-run with the feature default-on against
  a healthy throttled host is unchanged within noise — uniform cap 1.26x ideal (was 1.22x), slow
  region 2 conn 5.70 s / 4 conn 2.97 s (was 5.73 / 3.12 s), and peak workers stayed at full width
  (4/4), i.e. the gate never engaged without a throttle.
- Full `:download-engine:test` suite passes at **219 tests, 0 failures** (the 10 new adaptive tests on
  top of the prior 209).

### 4. Speed-limiter review

Confirmed adequate by the §3 sweep: with no cap set the limiter is one atomic-get + early return on
the hot path, and the throttled tail benchmark (per-connection caps) passes without a striped bucket.
A striped variant only pays for itself beyond ~16 *actively limited* workers, which the engine does
not reach in practice. No change.




---

## Phase 4 – HLS engine

Status: **in progress**. VOD MPEG-TS assembly was already correct; this phase closes the resilience
gaps the Phase-1 audit flagged in §10 and then widens container coverage. Every step is measured
against the local `TestStaticServer` harness (no third-party hosts), keeps the public
`HlsDownloader.Outcome` API stable, and adds new behaviour only as default-valued params or opt-in
settings.

### 4a. Segment-fetch correctness + resilience (this commit)

Three real bugs from §10 are closed, and the fix reuses the HTTP engine's own retry schedule instead of
inventing a second one.

**Truncated segments are no longer accepted as complete (§10 line 172).** `fetchBytes` returned partial
bytes for a `StreamResult.Truncated`, so a short body got written as a valid segment and concatenated
into a corrupt file. The fetcher now classifies every outcome through a private `Resource` sealed type
(`Ok` / `Retryable` / `Fatal`); a truncation is a `Retryable` failure that is re-requested, and it is
never written to disk short. `HlsSegmentResilienceTest` proves the segment is re-fetched and the final
file is byte-exact.

**Transient failures are no longer flattened to a fake `NotFound` (§10 line 167).** The old code mapped
*every* non-success to `null` → `DownloadError.NotFound`, and `NotFound` is not transient — so one 503
(or a reset) failed the whole run instantly, with no retry. `fetchResource` now propagates the real
`StreamResult.Failed` error together with its `retryAfterMillis` and `throttled` flag, and only genuine
permancies (404/401/disk) become `Fatal`. A 503 is retried; a 404 fails fast on the first answer.

**Per-segment retry now backs off, on the shared schedule.** The retry loop was a bare `while(true)`
with 3 immediate attempts — it hammered a throttling host. It now delays by `Backoff.equalJitterMillis`,
honouring a server `Retry-After` when present. To avoid a second copy of the arithmetic, the Phase-3
HTTP chunk backoff (`HttpDownloadCoordinator.chunkBackoffMillis`) and the HLS segment backoff now both
delegate to a new shared `org.blaze.engine.retry.Backoff` (identical equal-jitter math, base 1000 ms,
cap 30 s). The HTTP behaviour is unchanged — `RetryAfterAndBackoffTest` and the full suite still pass.

**Segment concurrency is configurable.** `HlsDownloader.maxConcurrency` was hard-wired at 4 and the
executor never set it. A new `DownloadSettings.hlsMaxConcurrentSegments` (default **4**, so nothing
changes until it is tuned) is wired through `DownloadExecutorImpl.executeHls`. The harness grew
per-path fault injection (`TestStaticServer.failFirst` / `truncateFirst` / `hitCount`) to drive these
cases over the wire.

**Verification:** `HlsSegmentResilienceTest` (3 tests) — truncated segment retried & assembled full;
transient 503 retried to success; permanent 404 fails fast with exactly one request and no output file.
Full `:download-engine:test` = **226 tests, 0 failures** (the 3 new on top of the 223 after the If-Range
commit); the shared-backoff refactor and the `HlsDownloader` fetch rework broke no existing HLS test.

### 4b. Single-track fragmented-MP4 assembly (this commit)

The §10 audit flagged that a `.m4s`/CMAF stream was refused outright because the container could not be
concatenated. A single-track fMP4 stream is already concatenable: its `#EXT-X-MAP` init segment
(`ftyp`+`moov`) followed by the media fragments (`moof`+`mdat`) in order forms one valid ISOBMFF file with
no remuxing. This adds that path without touching the public `Outcome` API.

**Model + parser (default-valued, no API break).** `HlsPlaylist` gains `initMapUri` / `initMapByteRange`
and each `HlsSegment` a `byteRange` (a new `HlsByteRange(start, length)`), all null-defaulted so every
existing constructor call still compiles. The parser reads `#EXT-X-MAP` (URI + optional BYTERANGE) and
`#EXT-X-BYTERANGE`. Crucially, the offset-omitted byterange form (`n`, meaning "continue after the
previous window") is resolved to an **absolute** `[start,length]` at parse time via a byte cursor —
fragments are fetched concurrently, so the continuation can never be reconstructed from fetch order.

**Downloader.** `detectContainer` returns `Fmp4` the moment an init map is present, before any byte sniff
(an fMP4 fragment starts at a box header, not `ftyp`, so the map tag is the only authoritative signal).
`fetchResource` now takes an optional `[start,end]` window and issues a ranged GET for a `byteRange`
segment. The init segment is fetched **once** and written ahead of the fragments in `assemble`; because it
defines the container, a failed init is a hard `Outcome.Failed` — never a silent skip that drops the `moov`
box into an unplayable file. Any container that genuinely needs a remux (not MPEG-TS, not init-bearing
fMP4) is still refused rather than faked into a broken file.

**Verification:** `HlsFmp4Test` (4 tests) — whole-fragment `.m4s` segments concat init-first, byte-exact,
init fetched exactly once; byte-range fragments sliced from one `.m4s` via three ranged 206 GETs re-join
byte-exactly after the init; a 404 on the init fails hard and writes no file; the parser resolves the
offset-omitted byterange to the correct absolute windows. Full `:download-engine:test` = **230 tests,
0 failures** (4 new on top of the 226 after §4a).

### 4c. Optional external remux (this commit)

A stream whose container Blaze cannot byte-assemble was (correctly) refused as `Unsupported`. This adds an
**opt-in**, cleanly-detected ffmpeg remux path for exactly those streams, without ever making ffmpeg a
dependency or touching the default path.

**Abstraction first.** `HlsDownloader` depends on a new `MediaRemuxer` interface (`isAvailable()` +
`suspend remux(parts, destination): RemuxOutcome`), never on a concrete process. The real implementation
`FfmpegRemuxer` locates the binary (explicit path from settings, else `ffmpeg` on PATH), probes availability
with `ffmpeg -version` under a short timeout, and remuxes via the concat demuxer with `-c copy` (lossless,
no transcode) through `ProcessBuilder`. Child-process output is drained on a daemon thread so the pipes
can never deadlock it; a wall-clock timeout `destroyForcibly()`s a hung ffmpeg. The output is written to a
`.remuxing` sibling and only moved onto the destination on a clean exit, so a failed remux leaves **no
corrupt file** — honouring the correctness rule.

**Strictly opt-in, off by default.** Two new `DownloadSettings` fields — `allowFfmpegRemux = false` and
`ffmpegPath = null`. `DownloadExecutorImpl.executeHls` constructs a remuxer **only** when the setting is
on, and passes it as the new (null-defaulted) `HlsDownloader.remuxer` constructor param. When the remuxer
is null, or `isAvailable()` is false, the branch falls through to the *unchanged* honest `Unsupported`
message — so a machine without ffmpeg, or a user who never enabled it, sees exactly the prior behaviour.
MPEG-TS and single-track fMP4 always take the pure-JVM concat path and never reach the remuxer.

**Verification:** `HlsRemuxTest` — a fake `MediaRemuxer` pins the decision tree without needing the binary:
available remuxer + needs-remux container → the already-fetched segments are handed over and the run
succeeds; unavailable remuxer → honest `Unsupported` and `remux` never invoked; no remuxer wired → the
prior `Unsupported` text stands; and an MPEG-TS stream with a remuxer available still concats without ever
calling it. A final test drives the real `FfmpegRemuxer` (availability probe + graceful handling of
un-remuxable input, leaving no corrupt destination) and **returns early when ffmpeg is absent**, so the
suite has no ffmpeg dependency. Full `:download-engine:test` = **235 tests, 0 failures** (5 new on top of
the 230 after §4b).

### Phase 4 status: complete

Tier-A segment resilience, single-track fMP4 assembly, and the opt-in external remux are all delivered,
test-backed, and green. No public API was broken (every addition is a default-valued param or an off-by
default setting). The engine remains pure-JVM by default; ffmpeg is never bundled or required.

---

## Phase 5 – Torrent engine

Status: **complete, awaiting review**. Branch `feat/engine-optimization`.

### 5a. Verify before change — the Phase-1 audit was wrong about `bt`

PROMPT Phase 5 requires: "**Verify what `bt` genuinely supports.** If something ... is missing or limited,
tell me plainly instead of faking it." So before touching code I extracted `bt-core-1.10-sources.jar` and
read the real classes. The Phase-1 audit (§11/§15/§17/§19) had asserted several capabilities as "missing, to
be enabled" that **do not exist** in 1.10, and one real gap whose fix it mis-attributed to a nonexistent
dependency. Correcting the record came first; the §11/§15/§17/§18/§19 text above is now the corrected version.

Non-existent APIs the audit claimed (all verified absent in source):
- `HashingStrategy.SAMPLED` / `bt.portability` / `SampledHashingStrategy` for fast resume — `bt.data.digest`
  is only `Digester`/`JavaSecurityDigester`/`SHA1Digester`. **No sampled hashing, no fast resume exists.**
- `EndGameStrategy` / `EndGamePieceSelectionStrategy` / `ExtensionsModule` / `setPieceSelectionStrategy` —
  absent; `Config.java` documents endgame as **built-in and automatic**.
- `WebSeedService` — no `WebSeed` class anywhere in `bt-core`.
- `bt-upnp-tracker-client` — **the artifact does not exist**; the real fix was already inside `bt-core`.

Claims the audit got backwards (real and now wired, no new dependency):
- **UDP trackers**: `bt.tracker.udp.UdpTracker`/`UdpTrackerFactory` (functional, handles `udp://`) are in
  `bt-core` but never ServiceLoader-registered. `maxOutstandingRequests=250` is the **library default**, not a
  Blaze over-set value.
- **MSE protocol encryption**: in `bt-core` via `Config.encryptionPolicy` (default `PREFER_PLAINTEXT`).
- **Local Service Discovery**: `bt.peer.lan.LocalServiceDiscoveryModule` is in `bt-core`.

### 5b. Wiring the real capabilities (this commit)

All three additions come from `bt-core`, which is already a dependency — **no artifact was added**, so the
"adding dependencies" review gate never fired.

- **`UdpTrackerModule`** (new file) binds `UdpTrackerFactory` for the `udp` scheme via
  `ServiceModule.extend(binder).addTrackerFactory(UdpTrackerFactory::class.java, "udp")`, mirroring how
  `bt-http-tracker-client`'s `HttpTrackerModule` registers HTTP. `TorrentRuntimeProvider.buildRuntime` now adds
  it alongside the HTTP tracker module, so a `udp://`-only torrent (the majority of public swarms) can announce
  instead of silently falling back to DHT/PEX.
- **`LocalServiceDiscoveryModule`** is added to the runtime so peers on the same LAN are discovered via
  multicast (TTL 1, 60 s interval; per-announce `IOException`s are caught inside the library, so it degrades
  gracefully where multicast is unavailable).
- **`applyPerformanceConfig` sets `encryptionPolicy = PREFER_ENCRYPTED`** — encrypted handshake first, plaintext
  fallback for legacy peers, helping on networks that shape/block plain BitTorrent without the compatibility
  loss of `REQUIRE_ENCRYPTED`.
- **`logTrackers`** now counts `udp://` alongside HTTP(S) as usable and only warns "peers can only come from
  DHT/PEX" when there is genuinely no usable tracker; its KDoc no longer claims UDP is unusable.

**Not changed, on purpose:**
- `maxOutstandingRequests` stays at 250 (the library default). The "reduce to 100" advice targets third-party
  strict peers; our localhost `bt`↔`bt` harness cannot reproduce them — `TorrentSwarmBenchmark` transfers 0 of
  32 MiB in 90 s here (in-process peers never connect over local discovery). Changing it would be tuning on an
  unmeasurable guess, so it is left and labelled an estimate.
- Fast resume / preallocation / web seeds / µTP: **not implementable** in `bt` 1.10. Stated as limitations,
  not faked. The multi-GB restart re-hash cost is inherent to this library version.

**Verification:** `UdpTrackerModuleTest` (4 tests, fully offline — a minimal tracker-only runtime, numeric
`udp://127.0.0.1` URLs that are parsed but never announced) pins that `udp://` is now a supported protocol and
resolves to a UDP tracker, HTTP(S) keeps working, and an unwired `wss://` is still honestly unsupported. The
existing torrent runtime tests (`TorrentDownloaderTest`, `TorrentPauseResumeTest`, `TorrentMetadataTest`,
`TorrentUrlSourceTest`) still build the real shared runtime with the new modules and pass. Full
`:download-engine:test` = **239 tests, 0 failures**.

**One timing-flake fix, unrelated to the torrent change.** `TimeoutRetryTest` (an HTTP-path test) failed only
in the full run and passed in isolation. Test classes share one JVM fork, and the torrent suite's process-wide
`bt` runtime (DHT bootstrap + LAN discovery) keeps daemon threads alive through its idle grace, which starved
CIO's event loop and delayed the socket-timeout callback past the test's 20 s wall clock. Since that test
checks retry *semantics* and not a latency SLA, its `withTimeout` was raised to 60 s (assertions unchanged) so
background load can no longer masquerade as a retry bug. No production code is involved.

### Phase 5 status: complete

The genuine Phase-5 wins — UDP trackers, LAN discovery, and opportunistic encryption — are wired from the
existing `bt-core` with **no new dependency**, test-backed, and green. Every Phase-1 claim about non-existent
`bt` APIs is corrected in place, and the real limitations (no fast resume, no preallocation, no web seeds, no
µTP; request-depth tuning unmeasurable on the localhost harness) are stated plainly rather than faked. No public
API or persisted format changed.
