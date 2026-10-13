# Blaze — HLS Streams & Remote ZIP Archives

**Audience:** users adding a stream or archive link, and contributors working on the two handlers. **Status:** first
implementation — see [Scope & limitations](#scope--limitations) for exactly what is
proven and what is deliberately left out.

Blaze can now take two link shapes that a plain file download would get wrong:

* an **HLS stream** (`http(s)://…/master.m3u8`) — a playlist that points at many small media segments,
  which Blaze fetches concurrently and stitches into **one playable file**; and
* a **remote ZIP** (`http(s)://…/bundle.zip`) — an archive Blaze can **browse over the network** and
  pull only the members you tick, without downloading the whole file first.

Both are reached through the same download flow as any other link, and both are decided by a **validated inspection**,
never by the file extension alone.

---

## 1. Add an HLS stream

1. **Add download** → paste the `.m3u8` URL → **Fetch**.
2. Blaze asks the engine to *inspect* the playlist (`inspectHls`) before anything is downloaded. The
   inspection resolves master → variant → media, lists the selectable renditions, and classifies how the
   stream is protected. It downloads **no media segment**.
3. Depending on the verdict:
    * **Playable** — the review step lists each rendition (resolution · bandwidth · language), reports the
      size estimate (`bitrate × duration`) for the one you pick, and names the destination. Choose a quality
      and confirm; Blaze fetches every segment with bounded concurrency and concatenates them into a single
      MPEG-TS file at the destination.
    * **Needs your acknowledgement** (see [DRM](#2-drm-detection-and-the-warning-dialog)) — a warning dialog
      appears first.
    * **Live / not-a-stream / error** — a readable message is shown and nothing is queued.

With no explicit pick, Blaze selects the **highest advertised bandwidth** variant, so "just download it"
yields the best quality the stream offers.

### What "HLS" covers here

| Supported                                                                      | Not supported (fails with a clear reason, never a half-baked file) |
|--------------------------------------------------------------------------------|--------------------------------------------------------------------|
| VOD (finite) media & master playlists                                          | **Live / event** playlists (`#EXT-X-ENDLIST` absent)               |
| Relative playlist & segment URL resolution                                     | Fragmented-MP4 / raw-AC-3 / other containers needing **remuxing**  |
| Multiple-variant master playlists                                              | Muxing audio+video into a new container                            |
| Ordinary `AES-128` segment encryption (key from the playlist `#EXT-X-KEY URI`) | `SAMPLE-AES`, FairPlay, Widevine, PlayReady (**DRM — refused**)    |

Blaze only claims single-file assembly for **MPEG-TS** containers. A stream whose segments are not
MPEG-TS is reported as unsupported rather than concatenated into something that merely *looks* done —
the container is decided from real downloaded bytes, not from a filename guess.

**No external executable is required** — segments are assembled by simple byte concatenation (valid for
TS), and AES-128 is decrypted with the JDK's own `javax.crypto`. Blaze does **not** shell out to FFmpeg.

---

## 2. DRM detection and the warning dialog

Some streams advertise protection (e.g. `#EXT-X-KEY:METHOD=SAMPLE-AES`, or a FairPlay/Widevine key URI).
Blaze *classifies* these — `None`, `OrdinaryEncryption`, `DrmSuspected`, `Uncertain` — and when the read
is **DrmSuspected** or **Uncertain**, `inspectHls` returns **NeedsDrmConsent** and the Add-Download dialog
raises a *"Potential DRM Protection Detected"* warning **before a single segment is fetched**.

The dialog is a **choice, not a block**, and its rules are strict:

* **Continue** records your acknowledgement and proceeds *only with the normal, supported download
  workflow*. Acknowledging never unlocks DRM circumvention, never defeats an access control, and never
  extracts protected keys — it changes **only what gets reported**, not what Blaze is able to do. A
  stream that is genuinely DRM-protected still fails, with the honest technical reason, after you continue.
* **Cancel** stays on the input step and starts nothing.

Blaze offers no link to a provider's terms from this dialog: the playlist alone cannot reliably identify
who is serving the stream, so the dialog instead points you to the provider's own documentation.

---

## 3. Browse and selectively download a remote ZIP

1. **Add download** → paste the `.zip` URL → **Fetch**.
2. Blaze inspects the archive (`inspectArchive`) by reading **only its central directory** over a handful
   of HTTP **byte-range** requests — the tail is fetched, the End-Of-Central-Directory located, then the
   directory itself. The full archive is **not** downloaded just to list it.
3. The review step shows the entry tree (folders and files, indented by depth) with each member's
   uncompressed size:
    * **Ranges supported** — every file gets a checkbox; pick files or whole folders (toggling a folder
      ticks everything under it), or **Select all**. The estimated size tracks your selection. On confirm,
      Blaze fetches *exactly* the byte windows those members occupy, inflates them, verifies each CRC, and
      writes them beneath the destination.
    * **Ranges not supported** — the tree is shown **read-only**, and Blaze explains why selective retrieval
      isn't possible. Downloading the whole archive is offered only behind an explicit *"Download the whole archive"*
      checkbox; the **Download** button stays disabled until you tick it, so a
      large fetch is never triggered silently.

Nothing is fetched from a browsable archive for a member you did not tick. An empty selection means
"the whole archive" and is represented as a null selection, never an empty list that could be misread as
"retrieve zero members".

### Archive variants & methods

| Supported                                                           | Reported as unsupported          |
|---------------------------------------------------------------------|----------------------------------|
| Standard 32-bit ZIP                                                 | Multi-disk / split archives      |
| **ZIP64** (saturated sizes/offsets resolved from the ZIP64 records) | Encrypted entries (flag bit set) |
| `Stored` (no compression) and `Deflate` members                     | Any other compression method     |
| Nested folder hierarchies                                           | —                                |

### Security safeguards (the archive is untrusted input)

Everything a remote ZIP declares is treated as hostile and re-checked at retrieval time:

* **Path confinement** — an entry name is rebuilt segment by segment beneath the destination; drive
  letters, absolute paths, `..`/`.` segments, backslash tricks and NUL bytes are rejected outright, and
  the normalized result is confirmed to still be under the root before anything is written.
* **Bounds** — every declared offset/size is checked against the real resource length before a range is
  requested; an out-of-range entry fails individually without aborting the others.
* **Compression-bomb guards** — decompressed output is capped by a per-entry size ceiling and a maximum
  inflate ratio; exceeding either fails that entry rather than filling your disk.
* **Integrity** — each member's CRC is verified after inflation; a mismatch (often meaning the archive
  changed since inspection) fails the entry instead of writing half-corrupt data.
* **Entry caps** — the parser refuses implausibly large entry counts / central-directory sizes so a
  hostile header cannot drive an unbounded allocation.

---

## 4. ZIP downloads, ISO downloads & ISO browsing — three different things

Blaze deliberately does **not** treat a ZIP and an ISO as "the same kind of archive".

* **A. Remote ZIP entry selection** — the feature above. It depends on the ZIP *central directory*
  structure and byte ranges, so individual members can be pulled without the whole file.
* **B. Ordinary ISO downloading** — an ISO image is **just a file**. It goes down the normal HTTP (S)
  handler exactly like any other large download, with chunked parallelism and resume where the server
  supports byte ranges. This downloads *the whole ISO*; it is not "selective". No separate ISO-download
  code exists, and none is needed — the existing ranged HTTP handler already covers it.
* **C. Browsing inside a remote ISO** — picking individual files *out of* an ISO would require parsing the
  filesystem inside the disc image (ISO 9660 and its extensions such as Joliet/Rock Ridge/El Torito). **This is not
  implemented.** It is tracked as a separate, optional enhancement so it never blocks the
  ZIP work.

> **Do not claim selective ISO extraction.** An ISO downloading fine, or a ZIP being browsable, says
> nothing about reading files inside an ISO. Only `.zip` links are probed by the archive inspector; an
> `.iso` is never sent to it and simply downloads as a whole file.

### Sketch of a future ISO-browsing implementation (not built)

Should it be taken up, it would reuse the existing plumbing and add only format-specific parsing:

1. A `RemoteIso9660Browser` counterpart to `ZipCentralDirectory`, reading the Primary Volume Descriptor +
   path table / directory records over ranged reads from the same `RemoteByteSource` abstraction.
2. A `SourceKind`/`DownloadSource.IsoImage` variant and an `inspectIso` API mirroring `inspectArchive`.
3. The **same** archive-browser UI and the **same** path-confinement / bounds / resource-cap checks in the
   extractor, with the tree source swapped from ZIP members to ISO directory records.
4. El Torito boot catalogs, sparse/high-water extents and UDF would remain out of scope initially, each
   reported honestly rather than guessed at.

---

## 5. Architecture — how the two handlers fit in

Nothing about the existing download machinery was forked; both features are new *source types* that ride
the current create → map → route → execute → persist path.

```
NewDownloadRequest(source)                         DownloadSource.{Url, Magnet, TorrentFile, Hls, RemoteArchive}
        │                                                     (polymorphic; @SerialName discriminator "type")
        ▼
RequestMapper.toEngineRequest                       → DownloadRequest.{Http, Torrent, Hls, Archive}
        ▼                                                     (exhaustive `when` — a new source is a compile error until routed)
DownloadManager.enqueue ──► scheduler ──► DownloadExecutorImpl
        is Hls     -> executeHls     -> HlsDownloader      (segments -> one MPEG-TS file)
        is Archive -> executeArchive -> ZipCentralDirectory + RemoteZipExtractor
                       (selectedEntries == null  =>  whole archive as an ordinary ranged HTTP download)
                       (selectedEntries != null  =>  inspect central dir, fetch only those byte windows)
```

* **Routing is type-driven.** A source is HLS or archive only when the *caller* — the Add-Download
  inspector, a resolver, or an explicit choice — declared it *after a validated inspection*. The
  `.m3u8` / `.zip` endings are **pre-inspection heuristics that decide only whether to probe**, exactly as
  the existing `.torrent` heuristic does. The engine's verdict, not the extension, decides what runs.
* **GUI flow.** The dialog gains per-operation `InspectHls` / `InspectArchive` /
  `AddDownloadWithSource` use cases (each the usual repository → Koin → screen-model layering). Inspection
  results live in `AddDownloadState` (`hlsInspection`, `archiveInspection`, chosen variant, selected
  members, the DRM/whole-archive acknowledgements) and are folded into the typed request by one pure
  function, `typedDownloadSource(state)`, which is unit-tested without Compose. A source edit clears every
  verdict and choice, so a stale variant or tick set can never leak into a different download.
* **Persistence & recovery.** `DownloadRequest.Hls`/`Archive` persist the non-secret facts needed to
  resume safely — the playlist/archive URL, chosen variant, selection, container, and per-segment/entry
  completion. Completed segments already on disk are reused on retry. **Credentials are never persisted**:
  an auth header or cookie travels only on the ephemeral in-memory request, stays out of the DTO, the
  database, the log and the portable metadata (verified by the round-trip persistence test). Saving state
  makes a task *restorable*; Blaze still re-validates the remote resource before claiming a resume works.

### Running the tests

```bash
# Engine: playlist parsing, HLS assembly, ZIP central-directory + selective extraction over a local
#         mock HTTP server, and the HLS/Archive persistence round-trip.
./gradlew :download-engine:test

# Service: source routing to the engine handlers, and credential-forwarding of the inspect calls.
./gradlew :download-service:test

# API: DTO/serialization contract for the new source and inspection types.
./gradlew :download-api:test

# GUI: the pure inspection-state / typed-source contract (no Compose/Koin needed).
./gradlew :gui:test --tests "org.blaze.presentation.screens.downloads.components.add.*"
```

Tests use **deterministic local fixtures and a local HTTP server** (`TestStaticServer`), not public
streaming sites, so they never depend on an unpredictable third-party host.

---

## Scope & limitations

Honest boundaries of this first implementation:

* **In-process only, for now.** The `inspectHls` / `inspectArchive` probes and the `hls` / `archive`
  sources are exposed through the in-process `DownloadsApi` the GUI uses. The **loopback HTTP transport**
  (`POST /api/v1/downloads`) still accepts only `url` / `magnet` / `torrentFile`, so a remote/CLI/browser
  client sending an `.m3u8` or `.zip` **URL** creates it as an ordinary file download (the whole file, no
  segment assembly, no selection) and has **no inspect endpoint** to call. Wiring those sources and probes
  onto the HTTP surface is straightforward follow-up work, deliberately left out to keep this change set
  reviewable.
* **HLS** supports **VOD MPEG-TS** streams only: live/event playlists and any container needing remuxing (fragmented
  MP4, etc.) are refused with a clear reason. DRM (`SAMPLE-AES`/FairPlay/Widevine/…) is **detected and refused**, never
  worked around; ordinary AES-128 is decrypted.
* **Remote ZIP** selective retrieval needs **server byte-range support**; without it Blaze offers only a
  user-confirmed whole-archive download. Encrypted entries, unsupported methods and multi-disk/split
  archives are reported, not extracted.
* **ISO browsing is not implemented** (see [§4](#4-zip-downloads-iso-downloads--iso-browsing--three-different-things));
  ordinary ISO downloads work as whole-file HTTP downloads.
* **Platform:** the feature code is pure JVM and cross-platform. The repository's automated tests are
  authored against Unix path conventions and a Linux autostart layout; on Windows some *pre-existing,
  unrelated* suites (`TorrentContentLayoutTest`, `OsIntegrationCleanupTest`) fail purely on
  path-separator expectations. These are environmental and touch none of the HLS/archive code paths.
