# Blaze — Operating-System Integration

How Blaze becomes a proper OS-registered handler for torrent files and magnet links, and how a
click in a browser or file manager travels into the download engine.

```text
Browser / File Manager
        ↓
OS File / URL Association          (.torrent, magnet:)
        ↓
Desktop launcher process           (gui/src/main/kotlin/org/blaze/Main.kt)
        ↓  ExternalOpenParser
ExternalOpenRequest                (os-integration module)
        ↓  SingleInstanceCoordinator (owner? forward? degrade?)
ExternalOpenInbox                  (startup-safe queue)
        ↓
Application Download API           (AddDownloadDialog workflow → DownloadsApi)
        ↓
Download Engine
```

The CLI keeps its independent, unchanged path: `CLI → local HTTP API → Application Download API → engine`.

---

## 1. Supported integrations

| Resource | How the OS delivers it | Status |
|---|---|---|
| `.torrent` file (`application/x-bittorrent`) | argv: `Blaze /path/file.torrent` (Windows/Linux), Apple event `odoc` (macOS) | registered on all three platforms |
| `magnet:?xt=urn:btih:...` URI | argv: `Blaze "magnet:?..."` (Windows/Linux), Apple event `GURL` (macOS) | registered on all three platforms |
| `file://…/x.torrent` | tolerated argv shape from some browsers | accepted, converted to the local path after validation |

Launch arguments:

* any number of `.torrent` paths and/or magnet URIs, mixed, in any order;
* `--new-instance` — dev escape hatch: skip single-instance ownership and open another GUI;
* unknown flags (`-psn_…`, JVM flags) are ignored and logged, never treated as resources.

## 2. The request model (`:os-integration`)

`ExternalOpenRequest` is the only currency between the launcher and the app:

* `TorrentFile(path)` — absolute, normalized, probed: exists, is a regular readable file, name ends in `.torrent` (case-insensitive), ≤ 4096 chars, no control characters.
* `MagnetUri(uri)` — kept **verbatim** (every `xt`/`dn`/`tr`/unknown parameter survives untouched). Validated: scheme exactly `magnet`, ≤ 8192 chars, no control characters, valid percent-encoding, `xt=urn:btih:` with a 40-hex or 32-base32 hash.

`ExternalOpenParser.parse(argv)` returns requests + typed rejections
(`RejectionReason`); one bad argument never loses the good ones. The GUI and the engine never see
raw argv, and the parser never executes anything or reads file *contents*.

**Adding a future file type or scheme**: one `ExternalOpenRequest` variant + one branch in
`classify()` + (per platform) one packaging declaration below. The inbox, IPC wire format and GUI
plumbing move opaque requests and never change.

## 3. Lifecycle

### 3.1 Entry point (`Main.kt`)

1. parse argv → requests (rejections logged, truncated/sanitized);
2. single-instance decision (below) — requests are already inside an `ExternalOpenInbox` either way;
3. macOS: register `MacOpenEventsBridge` (open-files / open-URI / dock-reopen AWT handlers) —
   **before** the first window, because warm opens arrive as Apple events, never as argv;
4. Windows: if running from a jpackage layout, `WindowsUrlSchemeRegistrar` re-asserts the per-user
   `HKCU\Software\Classes\magnet` registration (idempotent `reg add` with structured arguments);
5. normal startup continues (auto-start sync, Linux `.desktop` install, local API, Compose `application { }`).

### 3.2 Cold start

The inbox is a `StateFlow`: argv requests submitted before any UI exists simply *are* the queue the
first composition observes. Nothing can be lost in the startup window.

### 3.3 Warm start / single instance

* Ownership = an exclusive kernel `FileChannel.tryLock` on `<dataDir>/instance.lock`
  (deliberately **without** `DELETE_ON_CLOSE` — the JDK can unlink the path while the holder locks
  the orphaned inode, letting a newcomer "win"; see `InstanceLock` KDoc). POSIX locks die with the
  process, so a crash never wedges anything.
* A losing launcher forwards its requests to the owner and exits (code 0):
  AF_UNIX socket `<dataDir>/instance.sock` (Linux/macOS/Win10+), authenticated loopback-TCP
  fallback where unix sockets cannot bind. One JSON frame per connection, capped at 4 MiB / 64
  requests, carrying a `0600` shared secret (`instance-token`, `SecureRandom`, constant-time
  compare). **No unauthenticated network server exists.**
* The owner re-parses every forwarded value through the same `ExternalOpenParser` — the channel is
  authenticated, not trusted.
* If the owner holds the lock but never answers (mid-shutdown), the launcher degrades to starting
  its own GUI with the requests kept locally (`Unreachable`), retrying first for ~2 s. Never drop,
  never hang: reads are bounded by a watchdog timeout.

### 3.4 GUI workflow (what the user sees)

`BlazeApplication` observes the inbox: any pending request (or a Dock-reopen signal) raises the
existing window (`AppShell.raiseWindow` → `BlazeWindow` `raiseToken`). `DownloadsScreenContent`
surfaces queued requests **one at a time through the normal Add Download dialog**
(`AddDownloadDialog(initialSource = …)`): the field is pre-filled exactly as if pasted and metadata
is fetched automatically — then the usual destination choice, validation, conflict handling and
auto-start settings apply. A request is acked when shown; dismissing is the user's choice and does
not resurrect it. There is no parallel torrent-import path.

## 4. Platform registration

All packaging strings live in `gui/build.gradle.kts` (`nativeDistributions`) plus the two platform
classes in `:os-integration/platform`; no OS-specific literals are scattered through Kotlin UI code.

### Windows (MSI via jpackage)

* `.torrent`: `windows { fileAssociation(...) }` → MSI writes the registry during install,
  cleans it on uninstall; `upgradeUuid` pins identity so updates *replace* the old registration.
* `magnet:`: jpackage's association format cannot express URL protocols, so the *packaged launcher
  itself* (never an IDE run — `PackagedLayout` checks the `<app>/bin` + `<app>/lib/app` layout)
  registers `HKCU\Software\Classes\magnet` per-user on startup: exactly four idempotent
  `reg add` vectors, no admin, no shell string concatenation; open command
  `"C:\...\Blaze.exe" "%1"` passes the full URI as one argv slot (spaces/Unicode safe).

### macOS (DMG via jpackage)

* `.torrent`: `macOS { fileAssociation(...) }` → `CFBundleDocumentTypes` in the built `Info.plist`.
* `magnet:`: `infoPlist.extraKeysRawXml` injects `CFBundleURLTypes` (scheme `magnet`) verbatim.
* Cold *and* warm opens both arrive as Apple events (`oapp`+`odoc`/`GURL`), handled by
  `MacOpenEventsBridge` → shared parser/inbox. `main(args)` alone is never relied upon.
  Launch Services registration needs no code beyond the plist: Finder, browsers, *Open With*,
  `open -a Blaze file.torrent` and `open "magnet:?"` all resolve through it.

### Linux (Deb / RPM / AppImage + user-level `.desktop`)

* `linux { fileAssociation(...) }` gives the jpackage-generated menu entry its MIME type in
  deb/rpm builds.
* Additionally the app self-installs a user `~/.local/share/applications/org.blaze.desktop`
  (`LauncherEntryInstaller`, packaged launches only) with
  `Exec="<launcher>" %F` and `MimeType=application/x-bittorrent;x-scheme-handler/magnet;` —
  the xdg-standard claim on both the file type and the URL scheme; `kbuildsycoca6` is nudged on KDE.
* Verify/route with: `xdg-mime query filetype x.torrent`, `xdg-mime query default application/x-bittorrent`,
  `xdg-open "magnet:?xt=urn:btih:…"`. AppImage users: after the first packaged run the entry exists;
  `desktop-file-install` is not required.

## 5. Security assumptions

* Everything from the OS/browser is untrusted text: it is classified, never executed. No shell is
  ever involved (`ProcessBuilder` argument vectors only); no command is built by concatenation.
* Torrent files are *probed*, never opened, by the launcher; parsing of contents happens later in
  the engine's existing sandboxed read path.
* Magnets: strict scheme, length cap, control-character ban, percent-encoding validity, btih shape
  check; everything else preserved byte-for-byte.
* IPC: local transports only, `0600` token, constant-time compare, 4 MiB / 64-request frame caps,
  full re-validation on arrival, malformed UTF-8 rejected (strict `CharsetDecoder` REPORT).
* Windows registration is scoped to `HKCU\Software\Classes\<scheme>` with a `[A-Za-z][A-Za-z0-9+.-]{0,31}`
  scheme-name allowlist; uninstall leaves only per-user keys (removed with the user profile).

## 6. Testing

### Automated (`./gradlew :os-integration:test`)

* **Parsing**: argv shapes — normal launch, torrent, magnet, multiples, Unicode/spaces, drive
  letters, `file:` URIs, malformed magnets, unsupported schemes, control chars, length caps,
  `--new-instance`, per-argument failure isolation.
* **Routing**: `displaySource` equals the exact string the existing Add Download dialog accepts.
* **Startup queue**: submit-before-consumer, dedup, ack semantics (`ExternalOpenInboxTest`).
* **Single-instance forwarding**: real forked-JVM second launchers (POSIX locks are per-process,
  same-JVM simulation would pass for the wrong reason): Owner / Forwarded / Unreachable paths,
  token-forgery refusal, junk frames, abrupt disconnects, 4 MiB frame cap, invalid UTF-8,
  hostile payload re-validation, ownership release.

### Manual (from a dev machine; packaged builds)

```bash
# Linux
./gradlew :gui:packageDistributionForCurrentOS     # or packageAppImage/Deb/Rpm
/opt/Blaze/bin/Blaze /path/тест\ file.torrent &    # cold start → dialog appears
/opt/Blaze/bin/Blaze "magnet:?xt=urn:btih:<40hex>&dn=x"  # warm start → window raises, no 2nd instance
xdg-open "magnet:?xt=urn:btih:<40hex>"             # browser-equivalent
```
Windows/macOS: install the MSI/DMG, then exercise double-click, *Open With*, a browser magnet
click, and the same again with Blaze already running; uninstall and confirm the associations are
gone (Windows MSI) or point elsewhere.

## 7. Limitations / honest status

* Windows and macOS packaging blocks are verified at **configuration + unit level** (the DSL and
  generated plist/registry vectors); no Windows or macOS machine was available to execute the
  installers end-to-end.
* The Windows `magnet:` registration is re-asserted per-user at each packaged launch rather than
  written by the MSI — jpackage's association format cannot express URL protocols.
* `x-scheme-handler/magnet` is honored by XDG-spec desktops (GNOME/KDE/XFCE); window managers
  without a MIME database will still route `Blaze <args>` from file managers correctly.
* An `Unreachable` owner (mid-shutdown) yields a deliberate second-GUI fallback: degrading beats
  dropping the user's open.
