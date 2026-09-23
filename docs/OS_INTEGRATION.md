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
* `--cleanup` — no GUI at all: remove Blaze's user-level registrations and leftovers, report, exit
  (see [§5](#5-cleanup-end-of-a-session-end-of-an-install));
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
5. normal startup continues (auto-start sync, Linux `.desktop` install, local API, Compose `application { }`);
   before the launcher installs anything, `OsIntegrationCleanup.pruneStaleRegistrations()` drops the
   registrations this machine can no longer honour and the leftovers of a previously killed run;
6. on exit — including a SIGTERM, which skips the tail of `main()` entirely — the
   `blaze-session-cleanup` shutdown hook removes this process's single-instance channel, endpoint
   descriptor and stale `*.json.tmp` (never the shared secrets, never user data).

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

## 5. Cleanup: end of a session, end of an install

Every registration Blaze publishes lives under the user's own directories (or `HKCU`), which means it
outlives both the debug session that produced it and the package that installed it. Three passes cover
the three moments, all of them in `org.blaze.platform.cleanup.OsIntegrationCleanup`:

| When | Entry point | What it removes |
| --- | --- | --- |
| Every startup | `pruneStaleRegistrations()` | registrations this machine can no longer honour, plus the debris of a killed JVM |
| Every exit — including SIGTERM and the IDE's Stop button | shutdown hook `blaze-session-cleanup` → `sweepSessionFiles(includeSecrets = false)` | this process's single-instance channel, the API endpoint descriptor, abandoned `*.json.tmp` |
| On demand: `blaze --cleanup` / `./gradlew :gui:blazeCleanup` | `uninstallRegistrations()` | every user-level registration Blaze published, plus the IPC/API shared secrets |

**Removable** (all of it written by Blaze, all of it user-level):

* `~/.local/share/applications/org.blaze.desktop` — only when its `StartupWMClass` is ours;
* `~/.local/share/icons/hicolor/scalable/apps/org.blaze.<ext>` — only when no entry references it, which
  includes the formats an older Blaze shipped and the current one no longer names;
* `~/.config/autostart/org.blaze.desktop`, `~/Library/LaunchAgents/org.blaze.autostart.plist`,
  the `HKCU\...\CurrentVersion\Run` value — only what this app wrote;
* `HKCU\Software\Classes\magnet` — only when the handler launches Blaze's own executable;
* `instance.lock`/`instance.sock`/`instance-tcp`/`instance-token`, `api-endpoint.json`, `api-token`
  (the empty `api.lock` stays: keeping it is what makes lock-taking race-free, and it holds no data).

**Never touched**: an entry or handler Blaze did not author (a foreign `StartupWMClass`, another
player's `magnet:` claim), a registration whose launcher lives under an ephemeral root
(`/tmp/.mount_*` AppImage, `/Volumes`, `/media`, `/mnt` — those disappearing between runs is normal),
anything a *live* process still locks (the single-instance lock or `api.lock` — the sweep refuses and
says so), and the user's downloads, settings and logs, in every pass including `--cleanup`.

**Debug runs** are the special case, because a debugging session must not leave anything the user has
to discover by hand. Two leaks are now refused at the source:

* the hicolor icon is published only when a desktop entry actually references it, so `gradle run`
  leaves no orphan icon in the theme (a packaged launch keeps it, and repairs a live entry whose
  `Icon=` path has since gone missing);
* `AutoStartService.enable()` refuses an unlaunchable command — an interpreter (`java`, `javaw`, `sh`,
  `cmd`, …) or anything carrying `-classpath`/`--module-path` — so turning on "Run at startup" during
  development writes nothing instead of registering a JDK that fails at every sign-in.
  `pruneStaleRegistrations()` drops such an item if an older build left one behind, and
  `AutoStartCoordinator.sync()` immediately re-registers the launcher that is really running.

The same self-heal covers an uninstall performed outside Blaze: a `.desktop` entry whose absolute
`Exec` no longer exists is deleted along with the icon only it referenced (`kbuildsycoca6` is nudged),
a dead login item is dropped, and on Windows a `magnet:` key pointing at a deleted Blaze is removed so
the browser stops offering a handler that cannot start.

```bash
./gradlew :gui:blazeCleanup   # after uninstalling, or whenever the menu offers a Blaze that is gone
blaze --cleanup               # the same thing from an installed build: removes, reports, exits
./gradlew :gui:blazeCleanup -PblazeHome=/tmp/fake-home   # run it against a sandbox home, not your own
```

The pass is idempotent: run it twice on a machine with nothing left to clean and it reports
`nothing to clean up` rather than inventing a file to delete.

## 6. Security assumptions

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

## 7. Testing

### Automated (`./gradlew test`)

* **Parsing**: argv shapes — normal launch, torrent, magnet, multiples, Unicode/spaces, drive
  letters, `file:` URIs, malformed magnets, unsupported schemes, control chars, length caps,
  `--new-instance`, per-argument failure isolation.
* **Routing**: `displaySource` equals the exact string the existing Add Download dialog accepts.
* **Startup queue**: submit-before-consumer, dedup, ack semantics (`ExternalOpenInboxTest`).
* **Single-instance forwarding**: real forked-JVM second launchers (POSIX locks are per-process,
  same-JVM simulation would pass for the wrong reason): Owner / Forwarded / Unreachable paths,
  token-forgery refusal, junk frames, abrupt disconnects, 4 MiB frame cap, invalid UTF-8,
  hostile payload re-validation, ownership release.
* **Cleanup / uninstall**:
  * `:platform` — `DesktopEntryTest` + `LaunchCommandTest` (which `Exec`/`Icon` shapes count as ours,
    alive, installed or transient), `LauncherEntryInstallerTest` (a dead entry is pruned with its
    orphaned icon, an AppImage entry is kept, a debug run publishes neither entry nor icon,
    `uninstall()` leaves foreign files alone), the three autostart backends (each can read its
    registration back; a development command is refused before anything is written) and
    `AutoStartControllerTest` (prune only what is provably dead; report, never hide, an OS refusal);
  * `:os-integration` — `InstanceArtifactsTest` (what a sweep may delete, what it must not, verified
    against a genuinely separate JVM that owns the directory) and the unregister half of
    `WindowsUrlSchemeRegistrarTest` (own-handler check, stale-only mode, the `reg delete` vector);
  * `:gui` — `OsIntegrationCleanupTest` (all three passes over temp `~/.local`, `~/.config/autostart`
    and data dirs, including a second pass that must report nothing) and `AutoStartCoordinatorTest` (a
    dev login item is replaced, not merely removed).

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

## 8. Limitations / honest status

* Windows and macOS packaging blocks are verified at **configuration + unit level** (the DSL and
  generated plist/registry vectors); no Windows or macOS machine was available to execute the
  installers end-to-end.
* The Windows `magnet:` registration is re-asserted per-user at each packaged launch rather than
  written by the MSI — jpackage's association format cannot express URL protocols.
* `x-scheme-handler/magnet` is honored by XDG-spec desktops (GNOME/KDE/XFCE); window managers
  without a MIME database will still route `Blaze <args>` from file managers correctly.
* An `Unreachable` owner (mid-shutdown) yields a deliberate second-GUI fallback: degrading beats
  dropping the user's open.
* `--cleanup` is user-level by design. Anything a package manager owns (`/usr/share/applications`, the
  MSI's own `HKCR` keys, `/Applications/Blaze.app`) is left to the package manager, and Blaze's
  settings, logs and downloads stay behind for whoever reinstalls.
