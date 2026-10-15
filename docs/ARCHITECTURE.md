# Blaze Download Engine — Architecture

Design reference for the `download-engine` module and the layers around it. It describes the module map,
the end-to-end data flow, the concurrency/IO model, the **unified download state model** shared by every
protocol, the tuning knobs and their defaults, and the benchmark findings that justify the design. Line-level
optimisation history and full before/after tables live in [`PROGRESS.md`](./PROGRESS.md); this document is the
stable "how it fits together" map.

Scope: the engine supports **HTTP/HTTPS** (Ktor + OkHttp engine), **HLS** (m3u8, MPEG-TS and fragmented-MP4),
**Torrent** (the `bt` library, atomashpolskiy/bt 1.10) and **selective remote-archive** extraction. It is a
library; the desktop GUI and the CLI talk to it through an in-process service and a local HTTP API.

---

## 1. Module map

Only the modules that carry engine data flow are listed; the rest are UI/platform satellites.

| Module | Package root | Responsibility |
|---|---|---|
| `download-api` | `org.blaze.download.api` | The **public, wire-safe contract**: DTOs, the client `DownloadState` enum, events, errors, versioning. No engine types ever leak above this line. |
| `download-engine` | `org.blaze.engine` | Everything below the contract: orchestration, scheduling, per-protocol execution, network, storage, persistence, retry, settings, metrics. |
| `download-service` | `org.blaze.service` | Hosts the engine as an application service; maps engine types ↔ public DTOs (`PublicStateMapping`, `RequestMapper`), serves the local API, owns auth/instance-locking. |
| `cli` / `gui` | `org.blaze.cli`, `org.blaze` | Clients. Both consume only `download-api`; the GUI keeps its own 6-state display model derived from the public enum. |

Inside `download-engine`:

```
api/          DownloadTask, DownloadState (sealed), DownloadRequest (sealed: Http/Torrent/Hls/Archive),
              DownloadError, DownloadPriority, DownloadId
core/         DownloadManager  — the engine facade (enqueue/start/pause/cancel/remove, load/save)
scheduler/    DownloadScheduler — bounded-concurrency queue, owns Queued/Starting/Resuming/Pausing/Paused
execution/    DownloadExecutorImpl (one attempt per task → state flow), HttpDownloadCoordinator, HttpTransferPlanner
network/      HttpNetworkClient, TorrentNetworkClient, TorrentRuntimeProvider, UdpTrackerModule, BandwidthLimiter
hls/          HlsDownloader, HlsPlaylistParser, FfmpegRemuxer, MediaRemuxer
archive/      RemoteZipExtractor, ZipCentralDirectory, HttpRemoteByteSource
storage/      FileStorage, PositionedWriter, TorrentContentStorage
persistence/  DownloadRepository (downloads.json), HttpResumeState (per-file sidecar)
retry/        RetryPolicy, Backoff
settings/     DownloadSettings, EngineSettingsRepository (settings.json)
metrics/      EngineMetrics
portable/     portable-partial read/write helpers
```

---

## 2. End-to-end data flow

```
Client (GUI/CLI)
      │  download-api DTO / NewDownloadRequest
      ▼
ApplicationDownloadService ── RequestMapper ──▶ DownloadRequest (engine)
      │
      ▼
DownloadManager ──────────────▶ DownloadScheduler ──────▶ DownloadExecutorFactory
      │                                 │                        │
      │                          queueMutex (all                 ▼
      │                          mutations serialized)   DownloadExecutorImpl.execute()
      │                                 │                        │  channelFlow, per protocol
      │                                 │                        ▼
      │                          onTaskUpdated ──▶ DownloadTask state flow
      ▼                                                    │
DownloadRepository (downloads.json) ◀── persist ◀──────────┤
      │                                                    ▼
      └────────▶ ApplicationDownloadService emits DownloadEvent / DTO ◀── PublicStateMapping.toPublic()
```

Key invariants:

- **One executor per attempt.** `DownloadExecutorImpl.execute()` returns a cold `Flow<DownloadTask>`; each
  `collect` drives exactly one protocol path to exactly one terminal-or-requeue state.
- **The scheduler owns scheduling state** (`Queued`, `Starting`/`Resuming` boot, `Pausing`/`Paused`,
  `Cancelled`, priority); the executor owns **transfer state** (`Probing`, `Downloading`, `Merging`,
  `ResolvingMetadata`, `Seeding`, `Completed`, `Failed`). They never fight over the same field: priority in
  particular is re-read authoritatively inside the scheduler on every executor emission so a mid-flight
  `setPriority` is never reverted by a stale progress snapshot.
- **State is bridged, never shared mutably.** Downloader callbacks that run off the producer coroutine hand
  events back through channels; there is no lock and no shared mutable task between coroutines.

---

## 3. Concurrency and IO model

- **Coroutines throughout, structured.** A single `CoroutineScope` owns manager lifetime. Every "who is
  active" decision in `DownloadScheduler` (enqueue/start/pause/cancel/remove/setPriority) is serialized
  through one `queueMutex`, so a pause landing between flipping a task to a boot state and registering its
  job cannot leave an orphan job streaming after the user paused it. Jobs are started `LAZY` so the job is in
  the registry before its body (and its `finally` cleanup) can run.
- **Dispatchers.** The HTTP data path runs on `Dispatchers.IO` (`HttpDownloadCoordinator.download` is
  `withContext(Dispatchers.IO)`). Disk writes use positioned writes through `PositionedWriter`/`FileChannel`
  so segmented chunks land at fixed absolute offsets with no global write lock and idempotent retries/resumes.
- **HTTP engine = OkHttp** (chosen over the JDK client and CIO — see §8). Connection pooling is real: the
  requested connection count matches the server-side connection count, unlike CIO which opened a fresh socket
  per request.
- **Segmentation is bounded and honest.** Chunk workers = `connections`, capped by
  `EngineSettingsRepository.MAX_CONNECTIONS_PER_DOWNLOAD`; a stalled worker is dropped; adaptive shedding
  backs connections off under `429`/`503`. Work-stealing/late-splitting was measured and **deliberately not
  added** (the tail was already bounded; splitting only added coordination risk for no measured gain — §8).
- **Bounded, decoupled progress.** Per-segment throughput and the coarse phase boundaries are carried on
  channels so a slow UI consumer cannot stall a transfer (the service event buffer uses `DROP_OLDEST`). The
  HLS bridge uses an **UNLIMITED** channel rather than a conflated one precisely so the rare `Probing`/`Merging`
  boundary events can never be collapsed away by conflation.
- **Torrent runtime is process-wide and refcounted.** `TorrentRuntimeProvider` keeps one shared `BtRuntime`
  (DHT + HTTP/UDP trackers + PEX + LAN discovery) with a 60 s idle-grace teardown, so N torrents do not spawn
  N runtimes; `bt`'s own daemon threads are reconciled with the coroutine model through `awaitTorrentSettled`
  on pause/resume.

---

## 4. Unified download state model (Phase 6)

Two models, one mapping, no silent gaps:

- **Engine** `org.blaze.engine.api.DownloadState` — a `sealed interface` of `data object`s.
- **Public** `org.blaze.download.api.DownloadState` — a plain `enum` (serializes by **name**, so it is stable
  across transports/languages and mid-enum insertion is wire-safe).
- **Mapper** `PublicStateMapping.toPublic()` — a total `when` with **no `else`**. Adding an engine state is a
  compile error there, and again in the GUI's `DownloadRepositoryImpl.toGuiState()`. Those two exhaustive
  matches are the safety nets that make the "unification" real rather than aspirational.

### 4.1 The states

| State | Meaning | Emitted? | `isActive` |
|---|---|---|---|
| `Queued` | waiting for a slot / scheduled retry | scheduler | no |
| `Starting` | booting a cold transfer | scheduler | yes |
| `Resuming` | booting a transfer that already has bytes on disk | scheduler | yes |
| `Probing` | deciding *what/how* to fetch before any media byte moves | HTTP/HLS/Archive | yes |
| `Downloading` | transferring bytes | all | yes |
| `Merging` | post-transfer assembly (concat/remux, decompress) | HLS/Archive | yes |
| `Pausing` | tearing an active job down | scheduler | yes |
| `Paused` | resting, resumable | scheduler | no |
| `ResolvingMetadata` | torrent metainfo not yet known | Torrent | yes |
| `Seeding` | torrent finished, uploading | Torrent | yes |
| `Completed` / `Failed` / `Cancelled` | terminal for the current run | all | no |
| `Verifying` | **reserved** — no discrete verify pass exists | never | yes (if ever produced) |
| `Completing` | **reserved** — the partial→final rename is instantaneous, folded into `Completed` | never | no |

`Verifying` and `Completing` are kept **defined but never emitted** on purpose: HTTP never checksums, torrent
piece hashing runs continuously inside `bt` (not a separable phase), and HLS/Archive assemble without a verify
step. Emitting them would fabricate a phase the engine does not perform; the ground rules forbid that, so they
are documented as reserved (the same way the public enum has always treated `COMPLETING`).

### 4.2 Per-protocol transition tables

**HTTP** (accelerated):
`Queued → (Starting | Resuming) → Probing → Downloading → Completed | Failed`
`Probing` fires only where a real probe runs (the accelerated path, or resuming a chunked partial); the plain
single-stream path never claims it. The final partial→destination move is folded straight into `Completed`.

**HLS**:
`Queued → (Starting | Resuming) → Probing → Downloading* → Merging → Completed | Failed`
`Probing` spans the master→variant→media playlist walk; `Merging` spans the container sniff, init-segment
prepend, byte-concat, or the opt-in remux. DRM-suspect and unsupported containers surface as `Failed` with an
honest reason, never a faked file.

**Torrent**:
`Queued → (Starting | Resuming) → ResolvingMetadata → Downloading → (Seeding) → Completed | Failed`
The torrent's metadata resolution **is** its probing, so it keeps `ResolvingMetadata` rather than the HTTP
`Probing` label. Seeding appears only when seeding is enabled and the download is complete.

**Archive (selective)**:
`Queued → (Starting | Resuming) → Probing → Downloading → Merging → Completed | Failed`
`Probing` = `ZipCentralDirectory.inspect`; `Merging` = `RemoteZipExtractor.extract`. A whole-archive selection
delegates to the HTTP path (and therefore to HTTP's `Probing`).

**Scheduler-owned transitions (all protocols)**:
- Pause of an active task: `… → Pausing → Paused`.
- Start/retry/resume: `… → Queued → (Starting | Resuming)`; `Resuming` when `downloadedBytes > 0`, else
  `Starting`. Re-downloading a `Completed`/`Seeding` task first zeroes its stale progress.
- Cancel: `… → Cancelled`. A `Failed` task with retries left is re-emitted as `Queued` with a `scheduledAt`
  delay = `maxOf(policyDelay, serverRetryAfter)` and an incremented `retryCount`.

---

## 5. Persistence and compatibility

- **Task list** (`downloads.json`, via `DownloadRepository`) and **per-file HTTP resume** (`HttpResumeState`
  sidecar) are both written **write-temp-then-atomic-rename**, so a crash never leaves a half-written record.
- Download state persists as `state::class.simpleName.uppercase()` and reloads through `DownloadState.fromString`
  whose `else` branch yields `Queued`. **Consequences:** a new state name written by a newer engine degrades to
  `Queued` on an older reader (forward-compatible); `DownloadManager` on load maps `isActive → Queued` (with
  `resumeDownloadsOnStartup`) or `Paused`, so a crash mid-`Pausing`/`Merging` simply re-queues cleanly. No
  format migration is required.
- Portable partials carry a metadata region past the content; the resume cursor is the embedded content length,
  never the physical file size, so completeness decisions operate on real content bytes.

---

## 6. Tuning knobs (defaults from `DownloadSettings`)

| Knob | Default | Effect / why |
|---|---|---|
| `maxConcurrentDownloads` | 4 | Scheduler concurrency ceiling; shrinking it never interrupts running transfers, only defers new ones. |
| `maxConnectionsPerDownload` | 4 | Base per-transfer HTTP worker budget; hard-capped by `MAX_CONNECTIONS_PER_DOWNLOAD`. |
| `httpAccelerationEnabled` | true | Enables the probe + segmented path at all. |
| `httpMinParallelSizeBytes` | 4 MiB | Below this the extra requests of segmentation cost more than they save → single stream. |
| `httpChunkSizeMb` | 8 | Preferred work unit per connection; the planner shrinks it for small files. Clamped 1–256. |
| `httpStalledConnectionSeconds` | 30 | A connection idle this long while others progress gets dropped. Clamped 5–600. |
| `httpAdaptiveConnectionsEnabled` | true | Shed connections under `429`/`503` and win them back slowly; inert on a healthy host. |
| `hlsMaxConcurrentSegments` | 4 | HLS worker-pool width; modest to respect hosts' per-IP limits. |
| `allowFfmpegRemux` | **false** | Opt-in external remux for containers Blaze cannot concat. Off ⇒ the pure-JVM path never looks for ffmpeg. |
| `ffmpegPath` | null (PATH) | Explicit ffmpeg executable when remux is enabled. |
| `globalSpeedLimitEnabled` / `globalSpeedLimitKbps` | false / 10240 | Engine-wide token-bucket ceiling (`BandwidthLimiter`), arrives pre-configured so one download's limit does not depend on how many are running. |
| `snailModeEnabled` / `snailSpeedKbps` | false / 64 | Engine-wide crawl so the link stays usable elsewhere. |
| `autoRetryFailed` / `maxRetries` / `retryDelaySeconds` / `exponentialBackoff` | true / 3 / 5 / false | Task-level retry schedule (a server `Retry-After` always wins as a lower bound). |
| `resumeDownloadsOnStartup` / `startQueuedOnStartup` | true / true | What active/queued tasks become after a restart. |
| `fileConflictBehavior` | ASK | Skip / overwrite / rename / ask when the destination exists; the resolved name is persisted so resume stays pointed at one target. |
| `httpUserAgent` / `maxRedirects` | Chrome UA / 5 | Redirects are re-probed against the new Location without leaking auth headers cross-origin. |
| `maxPeerConnections` | 200 | Global bt peer budget. |
| `enableSeeding` / `seedTimeLimitMinutes` / `unlimitedSeeding` / `seedRatioLimit` | false / 30 / false / 0 | Post-completion torrent seeding policy. |
| `defaultDownloadPriority` | NORMAL | Ranks the queue; NORMAL keeps strict FIFO. Priority also scales a transfer's connection share. |

Settings persist to `settings.json` and are validated on write (`validate()`), which clamps each knob to a safe
range so a bad value cannot wedge the engine.

---

## 7. Resilience, progress and observability

- **Retry/backoff** is two-layered: chunk-level exponential **equal-jitter** backoff inside the HTTP
  coordinator (base 1 s, cap 30 s; a server hint short-circuits the schedule), plus task-level retry in the
  executor/scheduler. Transient answers (`5xx`/`429`/timeout/reset/truncated body) are retryable; `404`/`401`/
  disk-full are fatal — and a truncated segment is **never** written as if it were whole.
- **`If-Range` mid-transfer validation** catches a host rotating the representation *during* a segmented run:
  on a mismatch the mixed partial and its sidecar are deleted and the transfer re-probes and re-fetches against
  the new validator (bounded restarts), so a stitched-together corrupt file is impossible.
- **DRM honesty.** A *suspected* protected stream is a warning gated by user consent, never a block by itself
  and never a way around protection; Blaze decrypts only ordinary HLS AES-128 (JDK crypto) and refuses on
  concrete evidence (an undecryptable key scheme, a container needing remux with no remuxer), reporting the real
  limitation instead of faking a playable file.
- **Progress & speed** are exponentially weighted over the reported samples (not a fixed timer window), so short
  transfers report an honest speed; ETA derives from smoothed speed and remaining bytes.
- **Metrics** (`EngineMetrics`) count completions, failures and resumes; logging levels are engine-configurable
  (`LogLevel`). Public APIs carry KDoc; this file plus `PROGRESS.md` are the durable design record.

---

## 8. Benchmark findings that drove the design

Measured against the local loopback harness only (no third-party hosts). Full tables and methodology are in
`PROGRESS.md` Phases 2–3; the conclusions that shaped the architecture:

- **Ktor engine → OkHttp.** OkHttp won unthrottled throughput at every connection count (≈2× the JDK client at
  conn=1) and, decisively, is far lighter: **213 → 61 threads at conn=16**, real connection reuse, and lower GC.
  CIO was disqualified by connection reuse (34 server connections at conn=4 — a fresh socket per request). This
  is why production is OkHttp and tests use CIO.
- **Segmentation only pays on capped links.** On unthrottled loopback single-stream is the ceiling (~165–190
  MiB/s) and every extra worker is pure overhead; under a per-connection cap, more connections scale (conn=8/16
  lift throughput toward the aggregated cap). Hence the `httpMinParallelSizeBytes` gate and adaptive shedding.
- **Work-stealing was measured out, not assumed away.** The throttled tail already bounded to ~1.22× ideal and a
  slow final region drained in parallel across workers, so late chunk-splitting bought nothing and only added
  coordination risk — deliberately not implemented.
- **Torrent request-depth tuning is unmeasurable on this harness.** `maxOutstandingRequests` is left at the
  library default (250); the localhost `bt`↔`bt` swarm cannot reproduce the strict-third-party-peer condition the
  "lower it" advice targets, so changing it would be tuning on an unlabeled guess.

---

## 9. Known limitations (stated, not faked)

- `bt` **1.10** has **no** fast-resume/sampled re-hash, no disk preallocation, no web seeds, and no µTP; the
  multi-GB restart re-hash cost is inherent to this library version. The Phase-1 audit's claims about
  non-existent `bt` APIs (`EndGame`, `PieceSelection` strategy setters, `WebSeedService`, `HashingStrategy`) are
  corrected in `PROGRESS.md` §11/§15.
- `Verifying` and `Completing` exist in the model but are never produced (§4.1) because no operation backs them.
- The opt-in ffmpeg remux is the only external-executable path; it is off by default and only used when the
  binary is genuinely present.
- A host-dependent GUI test (`OsIntegrationCleanupTest`) fails on Windows because it asserts forward-slash paths
  while `Path.toString()` yields backslashes on this OS; it is unrelated to the engine and left as-is.

---

## 10. Adding a new protocol

The engine is open at exactly one seam and closed everywhere else, which is what keeps a new source from
touching the shared code:

1. Add a `data class` variant to the sealed `DownloadRequest`.
2. Add an `execute<Protocol>` branch in `DownloadExecutorImpl` returning a `Flow<DownloadTask>` that ends in one
   terminal-or-requeue state; map the protocol's genuine phases onto the existing states (`Probing`/`Downloading`/
   `Merging`/`ResolvingMetadata`/`Completed`/`Failed`) — emit a state only when a real operation backs it.
3. `when (request)` in `DownloadExecutorImpl` is exhaustive, so the compiler points at the one place to add.
4. No change is needed in the scheduler, persistence, metrics or the public mapping — the new protocol reuses
   them. Only if you introduce a **new state** do you get the two compile-forced mapper edits (§4.1), which is the
   intended place to reason about the wire/DB format.
