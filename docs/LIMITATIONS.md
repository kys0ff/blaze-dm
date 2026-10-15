# Blaze Download Engine — Limitations Register (Round 2, Phase 7)

Living register of every recorded limitation, gap, declined item, unmeasurable item and known bug,
with the evidence behind each and a class that decides who can fix it. It is built from `docs/PROGRESS.md`
**and re-verified against the current source** (the Phase-1 audit text is stale in several places; the
corrections are called out inline). Rule 1 of the round: every "exists / does not exist / the spec says"
claim carries a `file:line` or a BEP number checked against the official index
(`bittorrent.org/beps/bep_0000.html`, fetched 2026-10).

## Classes

- **A** — fixable now inside the engine (no external library change).
- **B** — fixable only by forking/extending `bt` (authorized this round; `third-party/bt/`).
- **C** — external constraint (server behavior, OS, protocol, a deliberate policy). Document only.
- **D** — declined on evidence, with the trigger that would reopen it.

Effort: **S** ≤ ~1 focused session-unit, **M** = a few, **L** = multi-session.
Risk = the correctness/regression surface a fix touches (data path, persisted format, public API).

## Citation corrections (Phase-1 audit → verified)

These Phase-1 statements are **wrong against today's code/spec** and are fixed in this register:

- **µTP is BEP 29** ("uTorrent transport protocol"), **not BEP 57** as `PROGRESS.md:188` states. Confirmed
  from the official BEP index.
- **Web seeds** are **BEP 19** (HTTP/FTP Seeding, GetRight-style) and **BEP 17** (HTTP Seeding, Hoffman-style,
  draft) — `PROGRESS.md:188` had the right BEP 19 but paired it with the wrong µTP number.
- **IPv6**: BEP 7 (tracker) and BEP 32 (DHT), both Draft.
- **fMP4 HLS is now supported** for single-track streams (`EXT-X-MAP` init prepended to fragments, byte-exact
  concat). `PROGRESS.md:159` "fMP4 is intentionally unsupported" is **stale** (Phase-4 commit `3512d0f`).
  Evidence: `HlsDownloader.kt:162-164,189-207`, `detectContainer` at `:523`.
- **HLS segment fetch-time truncation was fixed** in Phase-4 (`Resource.Retryable` on `Truncated`, never written
  as success — `HlsDownloader.kt:475-479`); the **resume-time completeness check** (see HLS-2) was then fixed in
  **Phase 7.5** (temp-name + atomic rename + a `.done` length marker, verified by a crash-resume integration test).
- The Phase-4 *plan* (`PROGRESS.md:355-356`) promised HLS assembly via `PositionedWriter` at absolute offsets.
  It was **not done** — assembly is still write-twice concat (see HLS-1).

---

## 1. Torrent (`bt` 1.10)

### TOR-1 — No fast resume; full re-hash on every restart — **B**, M, risk: medium
- **Evidence**: `PROGRESS.md:204,240`; resume re-verifies every piece against embedded hashes; `HashingStrategy`/
  `bt.portability`/`SampledHashingStrategy` **do not exist** in `bt-core-1.10` (`bt.data.digest` is only
  `Digester`/`JavaSecurityDigester`/`SHA1Digester`). Portable marker hint is a count only, always re-verified
  (`TorrentNetworkClient.kt:683-687`).
- **Fix (fork, Phase 8b.1)**: persist per-torrent verified-piece bitfield + each file size+mtime; on restart trust
  the bitfield only if size+mtime match, spot-check N random verified pieces, else full re-hash. Pluggable store.
- **Blocked by**: TOR-8 (must measure restart on a swarm harness that actually transfers). *Unblocked in
  Phase 8a — TOR-8 is fixed; measure on `SwarmHarness` / `LocalSwarmTransferTest`.*

### TOR-2 — No torrent preallocation / early free-space check — **B**, M, risk: medium
- **Evidence**: `PROGRESS.md:203`; `bt` `FileSystemStorage`/`FileSystemStorageUnit` grow files on write, no sparse
  hint. (Note: **HTTP** preallocation *does* exist — `DefaultFileStorage.kt:47`, `setLength` sparse — so this is
  torrent-only.) No pre-flight free-space check on the torrent path.
- **Fix (fork, Phase 8b.2)**: configurable none/sparse/full; fail early with a clear error if free space insufficient;
  document NTFS vs APFS/ext4 differences.

### TOR-3 — No web seeds (BEP 19 / BEP 17) — **B**, L, risk: medium
- **Evidence**: `PROGRESS.md:188`; no `WebSeed`/`WebSeedService` in `bt-core-1.10`.
- **Fix (fork, Phase 8b.3)**: parse `url-list`, map piece requests to HTTP Range requests (multi-file boundaries),
  integrate with selection/endgame, ban a seed on bad hash. The fork must NOT depend on Ktor — define a
  `WebSeedFetcher` interface in the fork, implement it in download-engine over the existing HTTP client (inherits
  retry/`Retry-After`/speed-limit).

### TOR-4 — No µTP (BEP 29) — **B/C**, design-first (Phase 8c), risk: high
- **Evidence**: `PROGRESS.md:188,317`; no µTP transport in `bt-core-1.10` (TCP peer transport only).
- **Fix**: no implementation until Gate (d). Phase 8c produces a µTP design + effort estimate and a libtorrent-binding
  comparison memo. LEDBAT + socket sharing with DHT + bt connection abstraction make this the single largest item.

### TOR-5 — Fixed outstanding-request depth (250), not adaptive — **B**, M, risk: low
- **Evidence**: `TorrentRuntimeProvider.kt:177` sets `config.maxOutstandingRequests = 250`; `Config.java:109` default
  is also 250, so this is the library default, not a Blaze over-set (`PROGRESS.md:195`). Test pins `>= 200`
  (`TorrentPerformanceConfigTest.kt:51`).
- **Fix (fork, Phase 8b.4)**: per-peer adaptive depth from measured throughput×RTT (bandwidth-delay product), bounded.

### TOR-6 — `maxOutstandingRequests` tuning is unmeasurable on the current harness — **D**, —, risk: n/a
- **Evidence**: `PROGRESS.md:195,328`; the localhost `bt`↔`bt` swarm transfers 0 bytes, so peer strictness can't be
  reproduced; changing 250 would be tuning on an unlabeled guess.
- **Reopen trigger**: TOR-8 fixed (a swarm harness that completes a real MiB-scale transfer, with injectable
  latency/throttle) → then measure 100 vs 250 vs adaptive. *The harness half landed in Phase 8a (real transfer
  works); injectable latency/throttle is still missing, so the trigger is not yet fully met.*

### TOR-7 — Pause/teardown latency ~0.5–1 s (polling in `awaitTorrentSettled`) — **B**, M, risk: medium
- **Evidence**: `TorrentNetworkClient.kt:596-638` runs `Thread.sleep` settle loops (NATURAL_SETTLE_MS=250, poll 25 ms,
  up to SETTLE_ROUNDS=4, plus 100 ms hold window); comment at `:707-709` notes it holds the **global queue mutex**, so
  it is pause/resume latency for *every* download, not just torrents.
- **Fix (fork, Phase 8b.5)**: expose a real "session stopped/unregistered" completion signal so the polling is removed;
  target < 100 ms pause on an idle torrent; re-verify the "Torrent already registered" race stays closed.

### TOR-8 — Local swarm harness completes 0 bytes (blocks every torrent measurement) — **A**, M, risk: low — ✅ FIXED (Phase 8a)
- **Evidence**: `TorrentSwarmBenchmark.kt:74` had one seeder + one leecher in a single `BtRuntime` relying on
  **LAN multicast discovery**; `downloaded` stayed 0.
- **Real cause (found in Phase 8a, worse than flaky — structurally impossible)**: one `BtRuntime` owns exactly one
  acceptor (`ServiceModule.java:272-277` binds `config.acceptorAddress:acceptorPort`) and one local identity
  (`PeerRegistry.java:81`). An in-process peer discovered by any source therefore carries the *same* address:port as
  the registry's own local peer, and `PeerRegistry.addPeer` drops it as `isLocal` (`PeerRegistry.java:222,250-252`).
  Multicast availability was beside the point.
- **Fix (Phase 8a, done)**: `SwarmHarness` runs seeder and leecher in **two** `BtRuntime`s with separate loopback
  acceptor ports, and gives the leecher a static `PeerSourceFactory` (bt's documented extension point —
  `ServiceModuleExtender.addPeerSourceFactory`) pointing at `127.0.0.1:<seeder port>`; no tracker, DHT or multicast.
  `LocalSwarmTransferTest` completes a real ~3.6 MiB **multi-file** transfer and verifies every file's SHA-256.
  `TorrentSwarmBenchmark` now measures for real (32 MiB in 3.15 s = **10.14 MiB/s**, loopback, this machine).
  This is the yardstick for TOR-1/5/6/7. No fork patch was needed.

### TOR-9 — Preview vs real metadata cache key mismatch (orphan leak + no reuse) — **A**, S, risk: low — ✅ FIXED (Phase 7.5)
- **Evidence**: real path writes `cache/${id.value}.torrent` (`DownloadManager.kt:332`) and cleanup deletes the same
  id-key (`:373-374`); the **preview** path (`fetchMetadata`) instead writes `cache/${sha256(source)}.torrent`
  (`:416-417`). So preview and real never share an entry, and the sha256-keyed preview files are **never cleaned**
  (leak) — matching `PROGRESS.md:268`.
- **Fix (Phase 7.5, done)**: one key derivation for both paths (sha256 of source string), cleanup deletes the real
  key, and the real download reuses a preview-cached `.torrent`. No persisted-format change. Leak-cleanup tests in
  `DownloadManagerTest`; confirmed in a real run (2026-10-15): preview cached `7276…841.torrent`, real download
  reused the same file.

### TOR-10 — No file/piece priority API surfaced through the engine request model — **B**, M, risk: low
- **Evidence**: `PROGRESS.md:199` — `PrioritizedPieceSelector`/`FilePiecePriorityMapper` exist in `bt` but Blaze does
  not override them; `DownloadRequest.Torrent.fileIndices` selects files but there is no ordering/priority exposure.
- **Fix (fork, Phase 8b.6)**: expose file selection + sequential/priority piece ordering via the fork public API; wire
  into the engine request model with default behavior unchanged. Multi-file selected-only test.

### TOR-11 — IPv6 peers / DHT unverified (BEP 7 / BEP 32) — **C**, S, risk: n/a
- **Evidence**: not exercised by any test; `bt` accepts IPv6 endpoints but Blaze never binds/tests them.
- **Action (Phase 8b.8)**: test whether it works over loopback `[::1]`; implement only if broken and the fix is small.
  Until proven, it is stated as **unverified**, not as "supported".

### TOR-12 — Real-swarm ERROR noise: unknown message types and resets logged as full stack traces — **C**, S, risk: low
- **Evidence (real run, 2026-10-15, nyaa swarm)**: a download completed with correct content while the log showed
  repeating `ERROR bt.net.DataReceivingLoop - Failed to process key` with `InvalidMessageException: Unknown message
  type ID: 70 / -59 / 29` and `SocketException: Connection reset`, plus `ERROR b.t.m.PieceConsumer - Failed to verify
  piece index {N} ... Will re-download.`
- **Analysis (fork sources)**: `StandardBittorrentProtocol.readMessageType` throws on any type byte outside 0–9/13–16
  (`StandardBittorrentProtocol.java:219-226`); `InboundMessageProcessor.processInboundData` rewraps as
  `RuntimeException` (`InboundMessageProcessor.java:93-96`); `SocketChannelHandler.read` already shuts the connection
  down on any read exception (`SocketChannelHandler.java:85-88, 171-181`) — the self-healing is correct, only the
  **logging** is wrong: routine hostile/legacy-peer churn is logged at ERROR with full stack traces
  (`DataReceivingLoop.java:130-132`), which reads like a defect to users. Piece-verify failures are the same churn
  (`PieceConsumer.java:121-123`); final content is hash-gated, so correctness is unaffected.
- **Fix (fork, Phase 8b.7)**: classify per-connection read failures — unknown message type → WARN one-liner with the
  message (peer still dropped, behavior unchanged); `SocketException`/`EOFException` churn → DEBUG. Keep genuine
  I/O-worker errors at ERROR. No protocol behavior change.

---

## 2. HLS

### HLS-1 — Assembly writes every segment twice (temp part, then copied to output) — **A**, M, risk: medium
- **Evidence (verified current)**: each segment is written to `tempDir/<i>.part` (`HlsDownloader.kt:340`) then read
  back and appended into the output `partial` in `assemble` (`:508-513`). Disk-write ≈ 2× stream size; `tempDir` must
  hold the whole stream. Phase-4 planned `PositionedWriter`-at-offsets; **not done**.
- **Fix (Phase 9.1)**: write each segment to its absolute offset in the destination partial via `PositionedWriter` as
  it arrives (segments are length-known from the manifest/EXTINF byte accounting), removing the temp copy and the
  second write; bounded memory.

### HLS-2 — Resume trusts `Files.size > 0` → a crash-truncated `.part` is accepted as complete — **A**, M, risk: high (correctness) — ✅ FIXED (Phase 7.5)
- **Evidence (verified current)**: `downloadSegments` skips any segment whose file size > 0 (`HlsDownloader.kt:325-330`);
  no expected-length record and no atomic rename. A crash mid-`Files.write` (`:340`) leaves a short `.part` that is
  silently concatenated. Fetch-time truncation is handled, resume-time is not.
- **Fix (Phase 9.1)**: record expected length or a completion marker per segment; write to a temp name and rename
  atomically on completion; re-download any segment failing the check. Test: simulated crash mid-segment.

### HLS-3 — Segments fully buffered in memory with a 32 MiB cap — **A**, M, risk: low
- **Evidence**: `fetchResource` collects into a `ByteArrayOutputStream` and throws when over `maxPlaylistBytes`
  (default 32 MiB, `HlsDownloader.kt:50,465-469`). A legitimately large segment is rejected rather than streamed.
- **Fix (Phase 9.6)**: stream segments over a threshold straight to disk (into the offset position per HLS-1) instead
  of buffering.

### HLS-4 — No `EXT-X-MEDIA` alternate audio/subtitle renditions — **A/B**, L, risk: medium
- **Evidence**: `resolveMediaPlaylist`/`chooseVariant` only walk `EXT-X-STREAM-INF` (`HlsDownloader.kt:236-242`);
  alternate-audio masters lose everything but video (`PROGRESS.md:160`).
- **Fix (Phase 9.2)**: parse `EXT-X-MEDIA`, download a selected rendition (default language or user-chosen). Multi-track
  mux is HLS-5/HLS-6.

### HLS-5 — WebVTT subtitles not handled — **A**, M, risk: low
- **Evidence**: no `EXT-X-MEDIA` TYPE=SUBTITLES and no `.vtt` fetch/segmentation in the parser or downloader.
- **Fix (Phase 9.2)**: segmented WebVTT assembly + `X-TIMESTAMP-MAP` when present.

### HLS-6 — Multi-track fMP4 (separate audio+video) cannot be muxed without ffmpeg — **C**, L, risk: medium
- **Evidence**: assembly handles single-track fMP4 only (`HlsDownloader.kt:162-164`); separate elementary tracks need a
  muxer. Opt-in ffmpeg remux exists for `Other` containers (`:170-182`).
- **Fix (Phase 9.2)**: with separate audio/video, mux with ffmpeg when opt-in and present; otherwise save tracks as
  separate files and say so. **Evaluate (do not assume)** whether a pure-JVM fMP4 audio+video muxer is realistic; if
  not, record that as the answer.

### HLS-7 — SAMPLE-AES unsupported (by policy, Rule 3) — **C**, —, risk: n/a
- **Evidence**: `cannotDecrypt` includes `HlsKeyMethod.SampleAes` (`HlsDownloader.kt:152`); refusal reason surfaced.
- **Decision**: support SAMPLE-AES only if keys are served openly via the playlist and decryption is well-specified
  (real key-extraction is prohibited for Widevine/FairPlay/PlayReady). Ordinary HLS AES-128 is decrypted with JDK
  crypto (`:590-595`). Documented, not faked.

### HLS-8 — Discontinuity handling not proven across boundaries — **A**, S(test), risk: low
- **Evidence**: `EXT-X-DISCONTINUITY` is parsed (`PROGRESS.md:155`) but there is no fixture proving correct output across
  a discontinuity for either TS or fMP4.
- **Action (Phase 9.5)**: discontinuity + key-rotation fixtures.

### HLS-9 — Key rotation implemented but under-tested — **A**, S(test), risk: low
- **Evidence**: per-segment `EXT-X-KEY` with rotation is honored in code (`fetchSegment` uses `segment.keyUri`, IV from
  sequence `:441`); no rotation fixture on the harness.
- **Action (Phase 9.5)**: AES-128 key-rotation + out-of-order/delayed delivery fixtures proving ordered reassembly.

### HLS-10 — Live / event HLS refused — **A**, L, risk: high
- **Evidence**: `media.isLive → Outcome.Unsupported` (`HlsDownloader.kt:126-128`); `inspect` reports `LiveUnsupported`
  (`DownloadManager.kt:476`).
- **Fix (Phase 9.3)**: playlist refresh at ~½ target duration, media-sequence tracking, no dup/skip, stop on `ENDLIST`
  or user stop, handle reload errors/sequence resets/discontinuities. Needs a simulated live server on the harness.

---

## 3. HTTP

### HTTP-1 — No cookie / session-header support — **A**, S, risk: low
- **Evidence**: no Ktor `Cookies` plugin anywhere on the path (`PROGRESS.md:242`); `PortableDownloadInfo` explicitly
  carries no cookies (`PortableDownloadInfo.kt:6`). Login-gated direct links fail with 403.
- **Fix (Phase 10.4)**: opt-in per request via the request model; **no cross-origin leakage on redirects**.

### HTTP-2 — No per-host connection budget across concurrent downloads — **A**, M, risk: medium
- **Evidence**: budget is per-download only; `PROGRESS.md:217` "No per-host cap across concurrent downloads".
- **Fix (Phase 10.5)**: shared per-host semaphore/limiter across active transfers to the same host.

### HTTP-3 — No per-download speed limit (only the global bucket) — **A**, M, risk: low
- **Evidence**: one global `BandwidthLimiter` bucket; `PROGRESS.md:267` "no per-download limit".
- **Fix (Phase 10.5)**: per-download ceiling under the global limiter.

### HTTP-4 — No user-supplied checksum verification; `Verifying` state never emitted — **A**, M, risk: medium
- **Evidence**: `DownloadState.kt:28-33` — Verifying is deliberately reserved ("HTTP never checksums… a future opt-in
  checksum feature"); no MD5/SHA-1/SHA-256 verification of a finished file.
- **Fix (Phase 10.6)**: post-download checksum against a user-supplied hash, emitting `Verifying` for real; mismatch
  fails with a clear error and keeps the file per a configurable policy. This also makes the reserved state honest.

### HTTP-5 — No probe caching on rapid restarts (HEAD + `bytes=0-0` GET every run) — **A**, S, risk: low
- **Evidence**: `PROGRESS.md:246`; probe is 2 round-trips before work on each run.
- **Fix (Phase 10.7)**: cache keyed by URL + validator with a short TTL.

### HTTP-6 — Single-stream path uses seek+write, not positional writes — **A**, S, risk: low
- **Evidence**: `PROGRESS.md:244`; one `RandomAccessFile` with `seek`+`write` per write instead of `FileChannel.write(buf,pos)`.
- **Fix (Phase 10.7)**: move single-stream to `PositionedWriter` (already used on the segmented path).

### HTTP-7 — No HTTPS in the test harness; TLS handshake / HTTP/2-vs-1.1 never measured — **A**, M, risk: low
- **Evidence**: harness is plain HTTP loopback (`TestHttpServer`); `PROGRESS.md:312` "measure first".
- **Fix (Phase 10.1)**: self-signed local HTTPS; re-measure per-request TLS handshake cost, connection reuse, and
  HTTP/2 vs HTTP/1.1 with OkHttp forcing each protocol; decide with evidence whether segmented downloads force HTTP/1.1.

### HTTP-8 — Throughput-driven connection ramp-up absent — **A**, M, risk: medium
- **Evidence**: connection count is planned up front; adaptive shedding exists (Phase 3) but never *grows* toward a cap.
- **Fix (Phase 10.3)**: start small, add connections while aggregate throughput keeps improving, stop on plateau or
  host push-back; must not regress the unthrottled-loopback case (one stream is already optimal there).

### HTTP-9 — Work-stealing / late chunk-splitting declined on evidence — **D**, L, risk: medium
- **Evidence**: `PROGRESS.md:806` — declined because no measurable tail bottleneck (uniform-speed benchmark);
  `ARCHITECTURE.md:252-254`.
- **Reopen trigger (Phase 10.2)**: re-test with **heterogeneous** per-connection speeds (0.5–8 MiB/s + one degrading
  mid-transfer). If tail overhead > ~15% of ideal, implement splitting the largest remaining chunk (min split size, safe
  sidecar/bitset) and re-measure; else record the negative result and close.

### HTTP-10 — Buffer pooling declined on evidence — **D**, S, risk: low
- **Evidence**: `PROGRESS.md:814` — no reproducible write-path cliff on the harness.
- **Reopen trigger (Phase 10.9)**: a deliberately slow/contended-disk benchmark showing a stall. Otherwise stays closed.

### HTTP-11 — Decoupled write queue declined on evidence — **D**, M, risk: medium
- **Evidence**: `PROGRESS.md:304,814` — more risk than reward on a fast local disk.
- **Reopen trigger (Phase 10.9)**: same slow/contended-disk benchmark.

### HTTP-12 — Mirrors / multi-source — **D**, L, risk: medium
- **Evidence**: `PROGRESS.md:308` — niche benefit, deferred.
- **Reopen trigger (Phase 10.8)**: only if cheap on top of the existing chunk scheduler; else keep class D with this trigger.

---

## 4. Platform / Quality

### QLTY-1 — `OsIntegrationCleanupTest` fails only on Windows (test bug, not a skip) — **A**, S, risk: none (test only)
- **Evidence**: the report's `describe()` renders `Path.of("/home/…").toString()` which yields **backslashes** on Windows
  while the assertion expects forward slashes. Known exception to the green suite (`ARCHITECTURE.md:270-271`).
- **Fix (Phase 11)**: make the test cross-platform (build the expected string with the platform separator or normalize).
  A genuine test bug; fix it, do not `@Ignore` it.

### QLTY-2 — `DownloadManagerTest` "remove-no-files" flake — **A**, S, risk: none (test only)
- **Evidence**: recorded as a flake in the round log. No deterministic reproduction yet.
- **Action (Phase 11)**: reproduce with a targeted test, or close with evidence that it is an assertion/teardown race.
  Never mask a flake by deleting the assertion.

### QLTY-3 — `EngineMetrics` not exposed through a stable public API — **A**, S, risk: low
- **Evidence**: `PROGRESS.md:221` — metrics are debug-only, never surfaced through `DownloadEngine`/`download-api`.
- **Fix (Phase 11)**: read-only public API with default-valued additions only (no break).

### QLTY-4 — JSON snapshot persistence won't scale to very large histories — **C/D**, L, risk: high (persisted format)
- **Evidence**: `PROGRESS.md:227` — full rewrite of `downloads.json` per debounced change; fine for thousands, not tens
  of thousands.
- **Action (Phase 11)**: benchmark at 10k and 50k records; change only if it is a measured problem, and **ask before any
  persisted-format change**.

---

## 5. bt fork — carry cost

Carrying a `bt` fork (Phase 8) buys TOR-1/2/3/5/7/10 but is a standing maintenance liability: rebasing onto upstream
`atomashpolskiy/bt`, keeping Apache-2.0 notices + `third-party/bt/PATCHES.md` current, and owning the correctness of any
µTP/muxing we add. Phase 8c's libtorrent-binding comparison memo is the honest alternative to weigh (native packaging on
Windows/Linux/macOS, licensing, interop maturity) before committing to years of fork upkeep.

---

## 6. Prioritized plan (for Gate b review)

Ordering is fixed by PROMPT-2's phase sequence; within that I flag the must-measure-first dependency.

| Pri | Items | Class | Effort | Gate/Phase |
|---|---|---|---|---|
| 1 | TOR-8 swarm harness (unblocks all torrent measurement) | A | M | **8a** → Gate c |
| 2 | Vendor `bt` fork (upstream import commit + PATCHES.md) | — | M | **8a** → Gate c |
| 3 | TOR-1 fast resume (benchmarked on the new harness) | B | M | 8b.1 |
| 4 | TOR-2 preallocation + free-space check | B | M | 8b.2 |
| 5 | TOR-3 web seeds BEP 19/17 (`WebSeedFetcher` in engine) | B | L | 8b.3 |
| 6 | TOR-5 adaptive request depth; reopens TOR-6 | B | M | 8b.4 |
| 7 | TOR-7 pause latency (real stopped signal) | B | M | 8b.5 |
| 8 | TOR-10 file/piece priority API | B | M | 8b.6 |
| 9 | TOR-11 IPv6 test-only | C | S | 8b.8 |
| 9a | TOR-12 swarm log-noise classification (fork patch #1-2) + guava bump (#3) | C | S | 8b.7 |
| 10 | TOR-4 µTP **design + libtorrent memo** (no code) | B/C | — | **8c** → Gate d |
| 11 | HLS-2 resume completeness (correctness) | A | M | **9.1** |
| 12 | HLS-1 write-once assembly via PositionedWriter | A | M | 9.1 |
| 13 | HLS-3 large-segment streaming to disk | A | M | 9.6 |
| 14 | HLS-8/9 discontinuity + key-rotation fixtures | A | S | 9.5 |
| 15 | HLS-4/5 `EXT-X-MEDIA` renditions + WebVTT | A | L | 9.2 |
| 16 | HLS-6 multi-track mux (ffmpeg opt-in / evaluate JVM muxer) | C | L | 9.2 |
| 17 | HLS-10 live HLS | A | L | 9.3 |
| 18 | HTTP-7 HTTPS harness + TLS/H2 measurement | A | M | **10.1** |
| 19 | HTTP-9 work-stealing heterogeneous-speed re-test (D→?) | A | M | 10.2 |
| 20 | HTTP-8 throughput-driven ramp-up | A | M | 10.3 |
| 21 | HTTP-4 checksum verify → real `Verifying` | A | M | 10.6 |
| 22 | HTTP-1 cookies (opt-in, no cross-origin leak) | A | S | 10.4 |
| 23 | HTTP-2/3 per-host budget + per-download limit | A | M | 10.5 |
| 24 | HTTP-5/6 probe caching + single-stream positional writes | A | S | 10.7 |
| 25 | QLTY-1 fix Windows test; QLTY-2 flake; QLTY-3 metrics API | A | S | **11** |
| 26 | TOR-9 metadata cache-key fix — **done in 7.5** | A | S | ~~11~~ 7.5 |
| 27 | QLTY-4 persistence scaling benchmark (ask before format change) | C/D | L | 11 |

**Held for your review at Gate (b).** No Phase-8+ code until you approve this register and the priority order.
