# Blaze Download API — Client & Transport Specification

**API version:** `v1` (base path `/api/v1`) · **Status:** stable within v1 · **Audience:** CLI, browser
extensions, scripts, desktop/mobile clients, any external integrator.

This document is self-contained: you can build a client (including a browser extension) without reading the
download-engine source. Everything below the `DownloadsApi` boundary — the HTTP/torrent engine, scheduler,
chunking, Ktor/JDK internals, persistence — is an implementation detail clients must never depend on.

---

## 1. Layered architecture

```
                    ┌────────────────────────────────────────┐
                    │  Download Engine (HTTP/HTTPS/Torrent)  │   internal, not client-visible
                    └──────────────────────┬─────────────────┘
                                           │  DownloadEngine SPI
                              Application Download Service
                                           │  implements DownloadsApi
                              ┌────────────┴──────────────┐
                              │   Public Download API     │   org.blaze.download.api.*
                              │  (DTOs, states, errors,   │   serializable, engine-free
                              │   events, capabilities)   │
                              └────────────┬──────────────┘
             ┌───────────────┬─────────────┼───────────────┬────────────────┐
             │               │             │               │                │
        In-process      Loopback HTTP  (future) Unix   (future) remote   browser
        (GUI, CLI       transport       socket / named  API              bridge
         direct)        /api/v1         pipe
```

* **`download-api`** — pure contract module: `DownloadsApi`, request/response DTOs, states, errors, events,
  capabilities. No engine, no coroutines-in-protocol, no HTTP. All clients share these types.
* **`download-service`** — the only implementation of `DownloadsApi` (`ApplicationDownloadService`), the
  loopback HTTP transport (`LocalApiServer`), authentication, path validation, the single-instance lock, and
  the headless daemon (`HeadlessDownloader`).
* **Clients** (`gui`, `cli`, future extension) depend on `download-api` types, and reach a running service
  over transport. They never construct an engine.

**Transport independence:** the same `DownloadsApi` backs the in-process GUI, the loopback HTTP server, and
any future socket/pipe/remote transport. Adding a transport never duplicates download logic.

---

## 2. Running the service & discovery

There is exactly **one** engine per data directory, guarded by an OS file lock
(`<storageDir>/api.lock`). Other processes connect to it instead of starting a competing engine.

The service runs in one of two ways:

| Mode                | How                                                                                                      | Notes                                                                                                                      |
|---------------------|----------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| **GUI**             | Launch the desktop app. It starts the engine and, best-effort, the local API on the same engine.         | If the API is disabled or the directory is already owned, the GUI still works; it just does not publish a second endpoint. |
| **Headless daemon** | `blaze-serve` (module `download-service`, main class `org.blaze.service.headless.HeadlessDownloaderKt`). | Runs the engine + loopback API with **no UI** — for CLI-over-service, scripts, servers, kiosks.                            |

`blaze-serve` flags: `--storage-dir/-d PATH`, `--port/-p N`, `--no-auth`, `--allow-origin ORIGIN`.

### Service descriptor (how clients find a running service)

When the API binds, it writes `<storageDir>/api-endpoint.json`:

```json
{
  "host": "127.0.0.1",
  "port": 41237,
  "apiVersion": 1,
  "pid": 88213,
  "startedAtEpochMs": 1760000000000,
  "tokenFile": "/home/user/.blaze/api-token"
}
```

and the **owner-only (`0600`) bearer token** to `<storageDir>/api-token` (kept in a *separate* file so the
descriptor never carries a secret).

Default `storageDir` is `~/.blaze`.

**Client discovery order** (used by the bundled CLI):
1. Explicit `--url` / `--token` (or `BLAZE_API_URL` / `BLAZE_API_TOKEN`).
2. The on-disk descriptor + the token file it points at.
3. If none is reachable → **do not** start a second engine; report *service unavailable* (exit `3`).

---

## 3. Authentication & security model

The local API is **localhost-only by default** and **authenticated by default**.

* **Binding:** `127.0.0.1` (never `0.0.0.0`). No remote access by default.
* **Bearer token:** a 256-bit value from `SecureRandom`, Base64URL-encoded, stored `0600`. Presented as
  `Authorization: Bearer <token>` (or `?token=` only as a websocket/browser-SSE fallback). Compared in
  **constant time**. Never hardcoded, never logged, never printed in responses.
* **DNS-rebinding guard:** every request's `Host` header is validated to be a loopback name/IP; a request
  arriving with a foreign `Host` is rejected `403` even if it reached the socket.
* **Origin guardrail:** if `allowedOrigins` is non-empty, requests carrying an `Origin`/`Referer` outside the
  allow-list are rejected `403`. CORS is a **convenience guardrail, never an authentication mechanism** — the
  token is still required.
* **Rate limiting:** sliding-window per client (default 240 req/min) → `429 RATE_LIMITED`.
* **Body cap:** requests over `maxRequestBytes` (default 1 MiB) → `413 PAYLOAD_TOO_LARGE`, rejected before
  parsing.
* **Filesystem confinement:** a client-supplied `destination` is validated against `allowedDownloadRoots`.
  When the roots list is empty (GUI-trusted mode) the local user's own permissions apply; when set (daemon /
  external clients) anything outside is rejected `403 PERMISSION_DENIED`. Path traversal and `NUL` are
  rejected. A client can never write arbitrary paths or execute commands via the API.

### Credentials vs. transfer metadata (§ browser-safety requirement)

The API deliberately separates:

* **`headers` / `referer` / `userAgent` / `accept`** — request headers the *transfer itself* legitimately
  needs. Persisted only as far as resume requires.
* **`credentials`** — short-lived auth material (cookies, `Authorization`, bearer tokens, signed-URL params).
  Forwarded **only** to the engine's in-flight request. **Never** written to the download database, never
  embedded in portable metadata, never echoed back in any DTO or HTTP response.

`SourceInfo.mayRequireCredentials` flags that a source needs fresh credentials on resume (so the client is
prompted again) instead of the service silently storing a stale secret.

---

## 4. HTTP transport (`/api/v1`)

Content type is `application/json; charset=utf-8` for all bodies unless noted.

| Method & path                                    | Auth | Purpose                         | Success                    |
|--------------------------------------------------|------|---------------------------------|----------------------------|
| `GET /api/v1/health`                             | no   | Liveness/version probe          | `200 {apiVersion, status}` |
| `GET /api/v1/capabilities`                       | no   | Feature discovery               | `200 Capabilities`         |
| `GET /api/v1/downloads`                          | yes  | List all downloads              | `200 [DownloadDto]`        |
| `POST /api/v1/downloads`                         | yes  | Create (see body below)         | `201 DownloadDto`          |
| `GET /api/v1/downloads/{id}`                     | yes  | Fetch one                       | `200 DownloadDto` / `404`  |
| `DELETE /api/v1/downloads/{id}?deleteFiles=true` | yes  | Remove (optionally delete data) | `200 {ok}`                 |
| `POST /api/v1/downloads/{id}/start`              | yes  | Start                           | `200 {ok}`                 |
| `POST /api/v1/downloads/{id}/resume`             | yes  | Resume                          | `200 {ok}`                 |
| `POST /api/v1/downloads/{id}/pause`              | yes  | Pause                           | `200 {ok}`                 |
| `POST /api/v1/downloads/{id}/cancel`             | yes  | Cancel                          | `200 {ok}`                 |
| `POST /api/v1/downloads/{id}/retry`              | yes  | Retry a failed download         | `200 {ok}`                 |
| `GET /api/v1/events`                             | yes  | SSE live event stream           | `200 text/event-stream`    |

`start` and `resume` both map to the API's `resume` operation (starting a queued or resuming a paused
download is the same engine action). Unknown action → `400 INVALID_REQUEST`; unknown version/path → `404`.

### Create download — request body

Exactly one source key (`url`, `magnet`, or `torrentFile`) is required, plus `destination`.

```json
{
  "url": "https://example.com/file.zip",
  "destination": "/home/user/Downloads",
  "filename": "file.zip",
  "autoStart": true,
  "connections": 8,
  "bandwidthLimitBytesPerSecond": 2097152,
  "expectedSizeBytes": 548576000,
  "referer": "https://example.com/products/42",
  "userAgent": "Mozilla/5.0 ...",
  "accept": "*/*",
  "headers": { "Accept-Language": "en" },
  "credentials": { "Cookie": "session=...", "Authorization": "Bearer ..." },
  "idempotencyKey": "browser-ext-9f2c1a"
}
```

| Field                                    | Type       | Required            | Notes                                                            |
|------------------------------------------|------------|---------------------|------------------------------------------------------------------|
| `url` / `magnet` / `torrentFile`         | string     | one of              | HTTP(S) URL, `magnet:` URI, or a server-readable `.torrent` path |
| `destination`                            | string     | yes                 | Directory; validated against allowed roots                       |
| `filename`                               | string     | no                  | Override the derived name                                        |
| `autoStart`                              | bool       | no (default `true`) | `false` → queued only                                            |
| `connections`                            | int        | no                  | Parallel HTTP connections                                        |
| `bandwidthLimitBytesPerSecond`           | long       | no                  | Per-transfer ceiling                                             |
| `expectedSizeBytes`                      | long       | no                  | Known size (e.g. from browser) for progress/ETA                  |
| `referer`,`userAgent`,`accept`,`headers` | string/map | no                  | Transfer headers (persisted as needed for resume)                |
| `credentials`                            | map        | no                  | **Ephemeral only** — never persisted (§3)                        |
| `idempotencyKey`                         | string     | no                  | Also accepted via the `Idempotency-Key` header                   |

`destination` is the directory to write *into*; the target is always named after the download
(`filename` when supplied). An HTTP file becomes `…/Downloads/file.zip`; a torrent becomes a folder
`…/Downloads/Big.Buck/` whose contents are laid out exactly as the torrent's metainfo describes them
— there is no second folder named after the torrent. `remove(id, deleteFiles = true)` deletes that
folder for a torrent, and the file plus its partial/resume sidecars for HTTP.

### Create download — response (`201`)

```json
{ "id": "8f3a…", "name": "file.zip", "state": "QUEUED", "source": { "kind": "HTTPS", "locator": "https://…", "mayRequireCredentials": false }, "destination": "/home/user/Downloads", "downloadedBytes": 0, "speedBytesPerSecond": 0, "peers": 0, "createdAtEpochMs": 1760000000000, "resumable": false, "portable": false }
```

---

## 5. Schemas (canonical, shared by every transport)

### `DownloadState` (explicit, stable enum)
`QUEUED, STARTING, DOWNLOADING, PAUSING, PAUSED, RESUMING, VERIFYING, COMPLETING, COMPLETED, SEEDING,
FAILED, CANCELLED`
* `isActive` — running/queued (not terminal).
* `isTerminal` — `COMPLETED | FAILED | CANCELLED`.
* `COMPLETING` is reserved for a future finalize phase; clients may ignore it. `SEEDING` is torrent-only.

### `DownloadDto`
| Field                        | Type                                               | Notes                                                                       |
|------------------------------|----------------------------------------------------|-----------------------------------------------------------------------------|
| `id`                         | string                                             | Opaque download id                                                          |
| `name`                       | string                                             | Filename / torrent display name                                             |
| `state`                      | DownloadState                                      |                                                                             |
| `source`                     | `SourceInfo{kind, locator, mayRequireCredentials}` | `kind` ∈ `HTTP, HTTPS, MAGNET, TORRENT_FILE, TORRENT_URL, HLS, ZIP_ARCHIVE` |
| `destination`                | string                                             |                                                                             |
| `totalBytes`                 | long?                                              | `null` if unknown                                                           |
| `downloadedBytes`            | long                                               |                                                                             |
| `progress`                   | float?                                             | 0.0–1.0, `null` if size unknown                                             |
| `speedBytesPerSecond`        | long                                               | instantaneous                                                               |
| `averageSpeedBytesPerSecond` | long?                                              |                                                                             |
| `etaSeconds`                 | long?                                              |                                                                             |
| `peers`                      | int                                                | torrent peers (0 for HTTP)                                                  |
| `error`                      | `ApiErrorBody`?                                    | present when failed / error-changed                                         |
| `createdAtEpochMs`           | long                                               |                                                                             |
| `completedAtEpochMs`         | long?                                              |                                                                             |
| `scheduledAtEpochMs`         | long?                                              | scheduled start                                                             |
| `resumable`                  | bool                                               |                                                                             |
| `portable`                   | bool                                               | portable-download artifact present                                          |
| `files`                      | `DownloadFile[]`?                                  | torrent file selection (`path, size, selected`)                             |

### `Capabilities` (`GET /api/v1/capabilities`)
```json
{
  "apiVersion": 1,
  "supportsHttp": true, "supportsHttps": true, "supportsTorrent": true, "supportsMagnet": true,
  "supportsPortableDownloads": true, "supportsPauseResume": true, "supportsCancel": true,
  "supportsRetry": true, "supportsScheduling": true, "supportsEventStream": true,
  "supportsHls": true, "supportsRemoteArchiveInspection": true,
  "bandwidthControl": "GLOBAL_ONLY",
  "prioritySupport": "FIFO_ONLY",
  "authentication": "BEARER_TOKEN",
  "requiresLocalhost": true
}
```

* `supportsHls` — the engine can download HLS (`.m3u8`) streams and inspect them for protection first.
  `supportsRemoteArchiveInspection` — the engine can inspect a remote ZIP via byte ranges and selectively
  retrieve entries. **Both are in-process capabilities** (`DownloadsApi`); the loopback HTTP transport has
  no inspect endpoint and its create body still accepts only `url`/`magnet`/`torrentFile` (§8).
* `authentication` ∈ `NONE, BEARER_TOKEN, PEER_CREDENTIALS`.
* `bandwidthControl` ∈ `NONE, GLOBAL_ONLY, PER_DOWNLOAD` — currently `GLOBAL_ONLY`, so
  `bandwidthLimitBytesPerSecond` on a create is a best-effort hint; the authoritative ceiling is the global
  engine setting.
* `prioritySupport` ∈ `NONE, FIFO_ONLY, EXPLICIT` — currently `FIFO_ONLY`; there is **no** per-download
  priority, which is why `setPriority` is not part of v1.
* `requiresLocalhost` is always `true` for the loopback transport.

Clients must read this instead of hardcoding assumptions; a v2 client negotiating with a v1 server can detect
the version mismatch via `apiVersion`.

### Error envelope (every non-2xx)
```json
{ "error": { "code": "DOWNLOAD_NOT_FOUND", "message": "no such download", "details": {} } }
```

| `ApiErrorCode`                                                                                             | HTTP    | Meaning                                         |
|------------------------------------------------------------------------------------------------------------|---------|-------------------------------------------------|
| `INVALID_REQUEST`                                                                                          | 400     | Malformed body/path/params                      |
| `INVALID_URL` / `INVALID_TORRENT` / `UNSUPPORTED_SOURCE`                                                   | 400     | Bad source                                      |
| `AUTH_REQUIRED`                                                                                            | 401     | Missing/invalid token                           |
| `PERMISSION_DENIED`                                                                                        | 403     | Origin/host rejected, destination outside roots |
| `DOWNLOAD_NOT_FOUND`                                                                                       | 404     | Unknown id                                      |
| `ALREADY_EXISTS` / `CONFLICT`                                                                              | 409     | Duplicate / directory owned by another service  |
| `PAYLOAD_TOO_LARGE`                                                                                        | 413     | Body over cap                                   |
| `RATE_LIMITED`                                                                                             | 429     | Too many requests                               |
| `NETWORK_ERROR` / `DESTINATION_UNAVAILABLE` / `DOWNLOAD_FAILED` / `SERVICE_UNAVAILABLE` / `INTERNAL_ERROR` | 4xx/5xx | Operation/transfer failure                      |

Engine exceptions are always mapped to these stable codes; internal class/exception names never leak.

---

## 6. Events / live updates

Polling is discouraged. Two equivalent streams expose the same `DownloadEvent`s:

* **In-process (`DownloadsApi.events(): Flow<DownloadEvent>`)** — GUI.
* **SSE `GET /api/v1/events`** — CLI `wait`, browser bridge. Each `data:` line is a serialized event:

```
data: {"type":"progress","downloadId":"8f3a…","downloadedBytes":4509715660,"totalBytes":5485760000,"speedBytesPerSecond":75916902,"etaSeconds":12,"progress":0.82}
```

| Event `type`                                               | Payload                                                                     |
|------------------------------------------------------------|-----------------------------------------------------------------------------|
| `created` / `completed`                                    | `download: DownloadDto`                                                     |
| `started` / `paused` / `resumed` / `cancelled` / `removed` | `downloadId`                                                                |
| `progress`                                                 | `downloadedBytes, totalBytes?, speedBytesPerSecond, etaSeconds?, progress?` |
| `failed`                                                   | `error: ApiErrorBody`                                                       |
| `errorChanged`                                             | `error: ApiErrorBody?`                                                      |

**Back-pressure:** progress events are **coalesced and rate-limited** at the application layer
(`eventMinIntervalMillis`, default 400 ms). A chatty observer cannot force the engine to emit faster than
that; lifecycle (non-progress) events are never dropped. The API server is **never in the byte-transfer hot
path** — it only creates/controls downloads and reads already-computed snapshots.

`observe(): Flow<List<DownloadDto>>` additionally provides a state-flow of the whole list for GUI-style
reactive binding.

---

## 7. Idempotency & duplicate handling

A browser extension that retries after a timeout must not create five identical downloads.

* **Explicit:** send `Idempotency-Key` (header) or `idempotencyKey` (body). Replays within the retention
  window return the **same** download id (the original response), never a duplicate.
* **In-flight source dedup:** a create whose active source (same url/magnet/torrent hash) already has a
  non-terminal download returns/attaches the existing one rather than double-queueing.
* Idempotency keys are **not** treated as content-identity: two arbitrary URLs are never assumed equal.

---

## 8. Public in-process API (`DownloadsApi`)

For Kotlin clients that share the JVM (the GUI), the same operations exist without HTTP:

```kotlin
val capabilities: ApiCapabilities
val apiVersion: Int
fun snapshot(): List<DownloadDto>
fun observe(): Flow<List<DownloadDto>>
fun events(): Flow<DownloadEvent>
suspend fun get(id): DownloadDto?
suspend fun create(request: NewDownloadRequest): DownloadDto
suspend fun start(id) / pause(id) / resume(id) / cancel(id) / retry(id)
suspend fun remove(id, deleteFiles: Boolean = false)
suspend fun probe(request): DownloadMetadataDto?          // size/name/metadata without downloading
suspend fun prepareTorrent(url, credentials): TorrentPreviewDto?  // fetch + parse a web-served .torrent
suspend fun inspectHls(url, variantIndex = -1, credentials): HlsInspectionDto    // read-only .m3u8 probe (no media segment)
suspend fun inspectArchive(url, credentials): ArchiveInspectionDto              // read-only remote-ZIP central-directory probe
suspend fun detectPortable(path): PortableInfoDto?
suspend fun importPortable(artifactPath, destinationDir): String?
suspend fun shutdown()
```

### Sources & inspection (HLS / remote ZIP)

`NewDownloadRequest.source` is a polymorphic `DownloadSource` (discriminator `type`): `url`, `magnet`,
`torrentFile`, and the two added here — `hls {url, variantIndex, drmAcknowledged}` and
`archive {url, selectedEntries}`. Declaring a source as `hls`/`archive` is what **selects the handler**;
Blaze never routes on a file extension alone. `inspectHls` returns an `HlsInspectionDto` whose `status` is
`Playable`, `NeedsDrmConsent`, `LiveUnsupported`, `NotAStream` or `Error`; `inspectArchive` returns a
sealed `ArchiveInspectionDto` (`Browsable{entries, selectiveSupported}`, `FullDownloadOnly{reason}` or
`Unavailable{reason}`). Both take **ephemeral** `credentials` that reach the transfer only and are never
stored, echoed or logged (`§3`). `drmAcknowledged` records a user's warning choice and gates only what is *reported* —
acknowledging never enables DRM circumvention.

> **Transport note.** These two sources and the `inspectHls`/`inspectArchive` probes are **in-process only**
> for now (used by the GUI). The loopback HTTP `POST /api/v1/downloads` body still accepts just
> `url`/`magnet`/`torrentFile`, and there is no HTTP inspect route — a remote client passing an `.m3u8` or
> `.zip` URL therefore gets an ordinary whole-file download. Exposing them over HTTP is deferred follow-up.

All lifecycle operations are serialized behind a per-id guard, so racing `start`+`pause`, `resume`+`cancel`,
`remove`+`completion`, or `retry`+`cancellation` can never leave inconsistent state. `move`/`rename`/
`setPriority`/`setBandwidthLimit` are **not** in v1 because the engine does not expose safe equivalents — they
will be added to the API when the engine supports them, without breaking clients.

---

## 9. CLI reference (`blaze`)

A pure client of the API above (never touches the engine, never starts a second one). Human output by
default; `--json` is the stable scripting surface.

```
blaze <command> [args] [options]

Commands
  download <url>            Queue an HTTP/HTTPS download
  torrent <magnet|file>     Queue a torrent (magnet URI or .torrent path)
  list                      List all downloads
  info <id>                 Show one download in detail
  pause <id> | resume <id> | cancel <id> | retry <id>
  remove <id> [--delete-files]
  status                    Show service health + capabilities
  wait <id>                 Block until the download reaches a terminal state (uses SSE)
  help

Download options
  -o, --output DIR          Destination directory (default ~/Downloads)
      --filename NAME       Override name
      --connections N       Parallel HTTP connections
      --bandwidth BYTES     Per-transfer speed ceiling (bytes/sec)
      --no-start            Queue without starting
      --referer URL / --user-agent UA

Global options
      --json                Machine-readable JSON (stable for scripts)
      --quiet / --verbose
      --url URL / --token TOKEN      Explicit endpoint (overrides discovery)
      --storage-dir DIR              Discovery dir (default ~/.blaze)
```

**Human example** (`list`):
```
#8f3a1c  ubuntu.iso   ████████████████░░░░ 81%   4.2 GiB / 5.1 GiB   72.4 MiB/s   ETA 12s   DOWNLOADING
```

**JSON example** (`info <id> --json`) — a raw `DownloadDto`, safe to `jq`/parse:
```json
{"id":"8f3a1c","name":"ubuntu.iso","state":"DOWNLOADING","source":{"kind":"HTTPS","locator":"https://…"},"destination":"/home/user/Downloads","totalBytes":5485760000,"downloadedBytes":4509715660,"progress":0.82,"speedBytesPerSecond":75916902,"etaSeconds":12,"peers":0,"createdAtEpochMs":1760000000000,"resumable":true,"portable":false}
```

### Exit codes (the scripting contract)

| Code | Meaning                                            |
|------|----------------------------------------------------|
| `0`  | Success                                            |
| `1`  | General failure                                    |
| `2`  | Invalid arguments / usage                          |
| `3`  | API/service unavailable (not running, unreachable) |
| `4`  | Download failed (from `wait`)                      |
| `5`  | Authentication failure (missing/invalid token)     |

Scripts must branch on exit codes and `--json`; never parse human-readable text.

---

## 10. Configuration

Stored at `<storageDir>/api.json`, deserialized as `LocalApiSettings`:

| Key                      | Default       | Purpose                                                          |
|--------------------------|---------------|------------------------------------------------------------------|
| `enabled`                | `true`        | Master switch for the local API                                  |
| `host`                   | `"127.0.0.1"` | Bind address (keep loopback)                                     |
| `port`                   | `0`           | `0` = ephemeral; the bound port is published in the descriptor   |
| `requireAuth`            | `true`        | When false → `AuthMode.NONE` (trusted single-user machines only) |
| `allowedOrigins`         | `[]`          | Empty = no browser-origin requests accepted (safe default)       |
| `allowedDownloadRoots`   | `[]`          | Empty = trusted (GUI) mode; set to confine external clients      |
| `maxRequestBytes`        | `1 MiB`       | Body cap                                                         |
| `eventMinIntervalMillis` | `400`         | Progress coalescing window                                       |
| `apiLoggingEnabled`      | `false`       | Verbose API logging (secrets are never logged regardless)        |
| `startOnBoot`            | `true`        | Start the API alongside the app                                  |

Defaults are secure: **localhost, authenticated, no allowed browser origins, no arbitrary destinations.**

---

## 11. Versioning & compatibility

* The path segment (`/api/v1`), `HealthResponse.apiVersion`, `Capabilities.apiVersion`/`minApiVersion`, and
  `ServiceDescriptor.apiVersion` all carry the version.
* **Compatibility within v1:** additive fields (optional, defaulted) may be added; enums gain values only at
  the tail; **no field is removed or repurposed** in v1. Clients should ignore unknown JSON fields
  (`ignoreUnknownKeys`) and tolerate unrecognized enum values where feasible.
* **Breaking changes** require `/api/v2`; v1 remains served during a deprecation window. Internal class names
  are never part of the contract — only the schemas in this document are.

---

## 12. What a browser extension must build (readiness)

Already available (nothing engine-side to change):
* `POST /api/v1/downloads` with url/magnet/torrent, `destination`, `filename`, browser-supplied
  `referer`/`userAgent`/`accept`/`headers`, and **ephemeral** `credentials`.
* `Idempotency-Key` for safe retries; `GET /api/v1/events` (SSE) for live status; `GET /api/v1/capabilities`.
* Token-based auth + loopback/origin/destination guards already enforced.

The extension implementer only needs to:
1. Read the local descriptor/token (or a pairing flow) to authenticate to `http://127.0.0.1:<port>`.
2. On browser download events / right-click, POST a create with the transfer headers it already knows.
3. Keep cookies/authorization in the `credentials` field (never `headers`) so nothing sensitive is persisted.
4. Subscribe to SSE for progress and render in the extension UI.

It must **not** and need **not** touch the engine, DTOs beyond this document, or any internal type.
