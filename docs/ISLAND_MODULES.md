# Island Modules

A layered, app-agnostic foundation for building JetBrains-"Islands"-style desktop shells on top of
Compose Desktop + Jewel. The `island-*` modules extracted from Blaze-DM contain everything the
reference IDE shell needs - palette/plugin theming, DWM caption pinning, tool windows, menus,
notifications, the search-everywhere framework and the window host - without dragging in Blaze's
download engine, i18n bundle or Koin graph. `org.island.demo` is a ~150-line consumer that boots
the full shell to prove the layering works for a project that is not Blaze.

## Layer diagram

Arrows read as "depends on"; every layer may import only from layers below it.

```
             ┌──────────────────────────────────────────────────────────┐
   apps      │  gui  ·  filepicker  ·  island-demo                      │
             └───────────────▲──────────────────────────────────────────┘
                             │
             ┌───────────────┴──────────────────────────────────────────┐
   shell     │  island-shell                                            │
             │  IslandWindow · IslandTitleBar · IslandHamburgerMenu     │
             │  IslandWindowSystemMenu · IslandWindowDrop               │
             │  IslandWindowHost                                        │
             └───────────────▲──────────────────────────────────────────┘
                             │
             ┌───────────────┴──────────────────────────────────────────┐
   ui        │  island-ui                                               │
             │  IslandPanel · IslandMenu · IslandDialog                 │
             │  ToolWindow{Layout,Header,Stripe,Options,SearchField}    │
             │  Notifications{State,Host,LocalNotifications}            │
             │  Search{Controller,Ranker,Popup,Models}                  │
             │  IslandToolbarIconButton · ExtensionIcon · IslandLayout  │
             │  IslandUiStrings (the string-injection contract)         │
             └───────────────▲──────────────────────────────────────────┘
                             │
             ┌───────────────┴──────────────────────────────────────────┐
   theme-ui  │  island-theme-ui                                         │
             │  IslandTheme (root composable) · JewelThemeAdapter       │
             │  IslandChromeColors · IslandSemanticColors               │
             │  IslandAmbientGradient · IslandComponentStyling          │
             │  IslandTypography · islandAmbientFrame modifier          │
             └───────────────▲──────────────────────────────────────────┘
                             │
             ┌───────────────┴──────────────────────────────────────────┐
   theme-api │  island-theme-api                                        │
             │  ThemePalette · IslandsPalette · IslandsDefaults         │
             │  ThemeAppearanceMode · ThemeOptions                      │
             │  IslandThemeProvider · ConfigurableIslandThemeProvider   │
             │  ThemeRegistry · SafeThemeProvider · ThemePluginLoader   │
             │  PaletteSanitizer · LoadedTheme                          │
             │  ThemeSettingsRepository (persistence port)              │
             │  color/ dominant-colour extractor                        │
             └───────────────▲──────────────────────────────────────────┘
                             │
             ┌───────────────┴──────────────────────────────────────────┐
   foundation│  island-foundation                                       │
             │  IslandPlatform (OS detection)                           │
             │  IslandWindowsNativeChrome (DWM caption + -nomod JBR)    │
             │  DialogChromeGuard                                       │
             │  IslandKeybindings (chord parsing + per-OS label fmt)    │
             └──────────────────────────────────────────────────────────┘
```

`island-foundation` and `island-theme-api` are pure JVM: no Compose, no Jewel, no Koin.
`island-theme-ui`, `island-ui` and `island-shell` add Compose + Jewel (int-ui-standalone +
decorated-window) but never Kotlin-inject anything. `island-demo` depends only on `island-shell`
and `island-theme-ui`, transitively pulling the rest.

## Per-module responsibility

### `island-foundation`

Everything that talks to AWT / JNA / the OS but not to Compose. Owns:

- **`IslandPlatform`** + `CurrentIslandPlatform` - the one OS enum the whole stack consults for
  Windows / Linux / macOS decisions and the per-OS capability bits (does the shell paint its own
  caption? does the tray fold minimize to hide?).
- **`IslandWindowsNativeChrome`** - the DWM caption / border / immersive-dark pinning that keeps
  the OS-drawn frame aligned with the active Islands theme, plus the reflective JBR helpers that
  survive a `-nomod` launch (no `--add-opens java.desktop/...`).
- **`DialogChromeGuard`** - the registry every `IslandDialog` reports into so the window chrome
  can tell a "user pressed the iconify button on the main frame" from a "the WM iconified the
  transient group because a dialog was open" and undo the second silently.
- **`IslandKeybindings`** - the generic chord model: parsing `"Ctrl+Alt+Delete"`, matching
  against a `KeyEvent`, and formatting modifier labels per OS (Windows `Ctrl`, Linux `Ctrl`,
  macOS `Cmd`/`Opt`/`Ctrl`/`Fn`). Blaze's own `BlazeKeymap` keeps only the action enum and the
  persisted-override registry on top of this.

### `island-theme-api`

The Compose-free palette + plugin contracts. Owns:

- **`ThemePalette` / `IslandsPalette` / `IslandsDefaults`** - the token shape (background,
  surface, accent, semantic-error / warning / info, plus the Islands chrome tokens) and the
  reference values the shell falls back to when a plugin omits a field.
- **`ThemeAppearanceMode` / `ThemeOptions`** - the light/dark/auto selection and the toggle for
  ambient glow, both stored as plugin-safe primitives.
- **`IslandThemeProvider`** - the interface a colour theme implements. Blaze ships three
  built-in providers (default / midnight / teal); a plugin jar drops in more.
- **`ConfigurableIslandThemeProvider`** - the wrapper that reads the mode + accent override at
  composition time so `Apply` in the host's Settings is reflected without a restart.
- **`ThemeRegistry` / `SafeThemeProvider` / `ThemePluginLoader` / `PaletteSanitizer` /
  `LoadedTheme`** - discovery, validation and the fallback wrapper that never lets one broken
  plugin jar poison the shell.
- **`ThemeSettingsRepository`** - the persistence port. A host wires it to its own storage (Blaze uses a JSON file under
  `AppSettingsRepository`); the island never assumes anything.
- **`color/` dominant-colour extractor** - the Celesti-style quantiser the shell uses to tint
  the title-bar brand icon's accent off whatever image the host hands it.

The light-mode-clobber rule (a plugin that omits `islandsPalette` must not overwrite the built-in
light variant) lives here, not in the composition layer, so it survives a theme-only refactor.

### `island-theme-ui`

The Compose/Jewel bridge. Owns:

- **`IslandTheme`** - the root composable. Installs `LocalIslandsPalette`, `LocalThemePalette`,
  the invisible-title-bar `ComponentStyling.decoratedWindow`, the overlay scrollbar styling, and
  wraps everything in `JewelTheme(isDark, globalColors, ...)` from the adapter below.
- **`JewelThemeAdapter`** (`buildJewelGlobalColors`) - maps the `ThemePalette` onto Jewel's
  `GlobalColors` so every Jewel widget (buttons, fields, tooltips, menus) picks up the palette.
- **`IslandChromeColors`** - the frame / island / island-border / islandHeader tokens the shell
  paints with. Every consumer reads these rather than a hard-coded hex; a themed plugin changes
  them for the whole app.
- **`IslandSemanticColors`** - the accent / hover / pressed / selected / error / warning / info
  set every interactive component reads.
- **`IslandAmbientGradient`** + **`Modifier.islandAmbientFrame`** - the ambient glow that
  anchors at the window's top-left corner and lifts the frame color under the active accent.
- **`IslandComponentStyling`** - the accent-driven override of Jewel's per-widget color sets.
- **`IslandTypography`** - the shell's font scale + audit hook.

No `org.blaze.*` import is allowed here, and no Blaze concept is named in code (comments may
mention Blaze as the reference consumer).

### `island-ui`

The base component library. Owns:

- **`IslandPanel`** - one "island" of the shell: the rounded-rect surface floating on the frame
  color, opt-in rim via `border=true`.
- **`IslandLayout`** - every chrome metric (radius, gaps, paddings, header heights, splitter
  widths). App facts (Blaze's `DownloadsColumn`, the details split fraction) live in Blaze's own
  module, never here.
- **`IslandMenu`** - the full 1126-line popup system: multi-level navigation, keyboard walk,
  accelerators, checkbox rows, submenus, mnemonic underlines, the shared
  `MenuWidthGroup` that lets every title-bar popup open at one width, and `MenuWidthPreloader`
  for the settle-before-first-frame behaviour. `MenuAnchor` is `internal`-constructed; the public
  `newMenuAnchor(initial: Rect = Rect.Zero)` factory exists so a caller can build a dynamic list
  of anchors inside a plain `remember` block.
- **`IslandDialog`** - the `DialogWindow` wrapper that reports into `DialogChromeGuard` and pins
  the DWM caption via `IslandWindowsNativeChrome`.
- **Tool-window system** - `ToolWindowSpec`, `ToolWindowLayout`, `FloatingToolWindow`,
  `ToolWindowHeader`, `ToolWindowOptionsButton`, `ToolWindowSearchField`, `ToolWindowStripe`,
  `ToolWindowLayoutStore` (persistence port) and `ContextMenuDismissGuard`.
- **Notifications** - `NotificationsState`, `NotificationHost`, `Notification`,
  `NotificationType`, `LocalNotifications`, `IslandClipboard` + `LocalIslandClipboard`. The
  error balloon's "Copy details" link writes through the injected clipboard so no island module
  imports Blaze's `SystemClipboard`.
- **Search-everywhere framework** - `SearchProvider`, `SearchResult`, `SearchCategory`,
  `IslandSearchController`, `IslandSearchRanker`, `IslandSearchPopup`. Blaze supplies its own
  `AppActions`/`DownloadsProvider`/`SettingsProvider` list; the shell never knows what a
  "download" is.
- **`IslandToolbarIconButton`**, **`ExtensionIcon`** (SVG via jsvg + raster fallback), **`CrashSafeContextMenu`**.
- **`IslandUiStrings` + `LocalIslandUiStrings`** - the string-injection contract; see below.

### `island-shell`

The parameterized app window. Owns:

- **`IslandWindow`** - one Jewel `DecoratedWindow` wrapper that pins the frame color to
  `IslandChromeColors.frame` on both Compose and AWT sides, calls
  `applyWindowsTitleBarTheme(window, frameColor)`, folds `ICONIFIED -> onMinimize` (with a
  `DialogChromeGuard.hasOpenDialog()` branch so a dialog caption minimize is just undone),
  raises the frame from `windowVisible + windowRaiseToken`, installs every
  `CompositionLocalProvider` the islands read (`LocalNotifications`, `LocalIslandUiStrings`,
  `LocalIslandClipboard`, `LocalWindowActive`, `LocalShortcutLabels`,
  `LocalAmbientGlowEnabled`), mounts the `NotificationHost` at `BottomEnd`, and takes four
  slots: `titleBar`, `content`, `contentOverlays`, `dialogs`.
- **`IslandWindowHost`** - the eight-member interface (windowState/Visible/RaiseToken + the six
  lifecycle callbacks) every consumer implements. Blaze's `AppShell` implements it verbatim;
  `island-demo`'s `DemoWindowHost` is a 25-line version.
- **`IslandTitleBar`** - the `DecoratedWindowScope` extension composable: `TitleBar` with the
  `islandAmbientFrame + newFullscreenControls` modifier, an app-icon button (that also opens the
  window system menu on both primary and secondary clicks), the hamburger, and a
  `leadingCollapsedContent` slot for widgets the app wants hidden while the menu is expanded.
- **`IslandHamburgerMenu`** - the collapsed button / expanded headers toggle. Owns the
  `MenuWidthPreloader` seed, one anchor per category (via `newMenuAnchor` inside
  `remember(categories)`), and the `headerBounds` + `onHeaderHovered` map keyed on the integer
  index so `IslandMenu`'s `Map<out Any, Rect>` contract stays generic. No-op on macOS (the
  platform's native app menu owns that role).
- **`IslandMenuCategory(label, entries: (onExecuted: () -> Unit) -> List<MenuEntry>)`** - the
  one shape a host hands the shell for each title-bar popup. `entries` is a **plain** function,
  not `@Composable`, so the width preload can call it inside a non-composable `remember`.
- **`IslandWindowSystemMenu`** + **`islandWindowMenuEntries`** - the square OS-style menu the
  app icon opens (Restore / Minimize / Maximize / Close, with the enable/disable rules the
  Windows system menu enforces), rendered through `IslandMenu(unifyWidth = false, squareCorners
  = true)` so it does not join the app-wide width group.
- **`rememberIslandWindowDrop(classify, onDropOutcome, hints)`** + **`IslandDropOverlay`** + **`IslandWindowDrop`** (+
  the `IslandWindowDrop.None` sentinel) - the whole-window
  drag-and-drop plumbing. Deliberately built on Compose's own `Modifier.onExternalDrag` (not
  AWT's `DropTarget`) because on Linux the AWT target never fires - drops are delivered to the
  Compose surface. Owns the per-session peek cache (`peeked === transferable`), the
  javaFileListFlavor / stringFlavor / exotic-text extract paths, the `hasReadablePayload` gate,
  the animated overlay (fadeIn 120 / scaleIn 150 0.985f, accent 0.07 / 0.55 / 0.65), the
  `MaxPreviewNames = 5` truncation and the `shortDropLabel` heuristic (magnet → `+12`,
  `://` → `take(48)`, otherwise `File.name`). A host wires `classify: (String) ->
  IslandDropIntent?` around its own parser and reads accepted payloads back out of
  `onDropOutcome(accepted: List<IslandDropIntent>, rejectedNames: List<String>): Boolean`.

## OS matrix

The behaviour below is documented in code where it matters; this is the summary layer.

| Concern                               | Windows                                                             | Linux (KDE / GNOME)                                                        | macOS                                                       |
|---------------------------------------|---------------------------------------------------------------------|----------------------------------------------------------------------------|-------------------------------------------------------------|
| Frame caption                         | DWM-pinned via `IslandWindowsNativeChrome` (immersive dark, accent) | WM-drawn, we do not touch it                                               | WM-drawn (native), we hide ours                             |
| Minimize / hide                       | `ICONIFIED -> host.onMinimize` via the shell's state listener       | Same listener; iconified group from a dialog is undone silently            | Native Dock behaviour; the listener still folds minimize    |
| Hamburger title-bar menu              | Full hamburger + categories                                         | Full hamburger + categories                                                | **No-op** - the OS application menu owns that role          |
| System-menu button (app-icon click)   | Opens `IslandWindowSystemMenu` on left AND right click              | Same                                                                       | Same (though the OS window controls are usually sufficient) |
| Tray hide / raise                     | `windowVisible=false` unmapps the Compose frame; raise via token    | Same, plus `extendedState = NORMAL` before `toFront` for leftover WM state | Same mechanism, but the Dock usually handles show/hide      |
| Whole-window drop target              | `Modifier.onExternalDrag` catches files + URLs + magnets            | Same (this is why we do not use AWT's `DropTarget` - it never fires)       | Same                                                        |
| JBR reflection helpers under `-nomod` | Silent no-op (DWM path uses JNA)                                    | Silent no-op (uses AWT + window manager)                                   | Rarely needed; guarded by capability flags                  |

Where a rule is asymmetric across OSes, the code carries a comment naming the affected platform (e.g.
`IslandHamburgerMenu` early-returns for `IslandPlatform.MacOS`;
`DialogChromeGuard` is documented against the transient-group iconify bug that reproduces under
KDE but not GNOME's Wayland session).

## String-injection contract

Island components NEVER import `org.blaze.i18n` or read a bundle directly. Every user-facing
string is either:

1. **Passed as a parameter** (e.g. `IslandWindow(title = "Blaze", ...)`,
   `IslandDialog(title = "...")`), or
2. **Read through `LocalIslandUiStrings.current`** from the `IslandUiStrings` interface. The
   interface (in `island-ui/text/IslandUiStrings.kt`) has English defaults so a fresh host gets
   a working, readable shell without wiring anything. The `IslandUiStrings.Default` companion is
   that plain-English instance.

A localised host (Blaze) writes a thin adapter class - Blaze's is `BlazeIslandStrings(blaze)` in
`gui/presentation/components/` - that overrides every accessor to read from its own
`BlazeStrings` bundle and provides it at the window root through `IslandWindow(strings = ...)`.
Components read only through the composition-local, so switching bundles needs no restart.

The currently-injected families:

- **Search-everywhere**: `searchEverywherePlaceholder()`, `searchTypeToSearch()`,
  `searchNothingFound()`, `searchTabAll()`, `searchFieldPlaceholder()`.
- **Status bar**: `connected()`, `offline()`.
- **Balloons**: `copyDetails()`, `close()`.
- **Tool windows**: `toolWindowHide()`, `toolWindowOptions.*` (View Mode, Move To, Resize).
- **Icons**: `extensionIcon()`.
- **Window shell**: `systemMenu()`, `mainMenu()`.
- **Window system menu**: `windowRestore()`, `windowMinimize()`, `windowMaximize()`,
  `windowClose()`.
- **Drop overlay**: `dropOverlayTitle()`, `dropOverlayUnsupportedTitle()`,
  `dropItemsMore(count)`, `dropQueued(count)`, `dropRejected(names)`, `dropNothingUsable()`.

## Persistence ports

Nothing in the island modules writes to disk. Where a component needs to remember state across
sessions the interface is declared in the island and implemented by the host:

- **`ThemeSettingsRepository`** (`island-theme-api`) - the active theme id + appearance mode +
  accent override.
- **`ToolWindowLayoutStore`** (`island-ui`) - the docked / floating / split state of every tool
  window the app declares.

Blaze wires both to its own JSON file under `AppSettingsRepository`; a new host can start with
in-memory maps and grow from there.

## Keymap model

`IslandKeybindings` in `island-foundation/keymap/` owns the generic shape: parsing a chord
string, matching a `KeyEvent`, and formatting modifier labels per OS (`Ctrl` / `Cmd` / `Alt` /
`Opt` / `Supr` / `Meta` / `Fn`). A host keeps only the enum of actions and its persisted
overrides - see `gui/presentation/components/BlazeKeymap.kt` for the pattern. The shell does
not require an interface for this: `IslandWindow` takes an
`onPreviewKeyEvent: (KeyEvent) -> Boolean`, and Blaze closes over `BlazeKeymap.matchAction`
inside that lambda.

## Starting a new app in five minutes

1. Scaffold the module: `settings.gradle.kts` includes your project; `build.gradle.kts` applies
   `kotlinJvm`, `kotlinCompose` and `jetbrainsCompose`, then declares
   `implementation(project(":island-shell"))` and `implementation(project(":island-theme-ui"))`.
   Pin the JBR-25 `javaLauncher` + `executable` (see `gui/build.gradle.kts`) so Gradle does not
   fall back to a JDK Compose cannot load.
2. Copy `island-demo/src/main/kotlin/org/island/demo/Main.kt` - the ~150-line skeleton - into
   your `src/main/kotlin/`. Adapt the `menuCategories` list to your app's real menu (each
   `IslandMenuCategory(label, entries)` returns a `List<MenuEntry>`; `MenuEntry.Action(label,
   icon?, accelerator?, enabled?, checked?, mnemonic?, onClick)` and `MenuEntry.Submenu` cover
   the row shapes the shell draws).
3. Implement `IslandWindowHost` for your app's window-lifecycle shape. Blaze folds minimize
   into a tray hide; a normal app hides the frame directly - see `DemoWindowHost` for the
   minimum viable version.
4. Wrap your body in `IslandPanel` (one per visual "island"), pass them to `IslandWindow`'s
   `content` slot, and hand your app's icon / ImageVector to `IslandTitleBar`'s `brand` slot.
5. Provide an `IslandUiStrings` implementation only when you need a localised shell. The
   `IslandUiStrings.Default` instance is enough to launch.
6. Optional seams:
    - **Drop**: `rememberIslandWindowDrop(classify, onDropOutcome, hints)` -> pass the result to
      `IslandWindow(drop = ...)`.
    - **Search-everywhere**: build your `List<SearchProvider>` and mount `IslandSearchPopup`
      inside `IslandWindow(contentOverlays = ...)`.
    - **Notifications**: any surface reads `LocalNotifications.current` and safe-calls
      `.info(msg)` / `.warning(msg)` / `.error(msg)` on it (the local is nullable by design so an
      isolated component preview keeps working).
    - **Tool windows**: `ToolWindowSpec` + `ToolWindowLayout` (docked/floating) driven by your
      own `ToolWindowLayoutStore`.

`./gradlew :island-demo:run` boots the reference - if the demo window appears with the flat
frame, the ambient glow, the working title-bar menu, and the balloon stack, every one of the
island layers is correctly wired for your app too.

## What is deliberately NOT here

- **Koin / DI**: wiring lives in Blaze's `gui/di/*` modules; the shells take their
  collaborators as parameters or CompositionLocals. Nothing under `org.island.*` imports Koin.
- **i18n bundles**: see the string-injection contract above.
- **`ExternalOpenParser` / `ExternalOpenInbox`**: Blaze's `os-integration` module owns the
  magnet / torrent / link-list parsing. The shell's drop seam is a plain `classify: (String) ->
  IslandDropIntent?` so any host can wire any parser.
- **Tray / system notifications**: `tray` and `notifications` modules are already app-agnostic
  and unchanged this round; the shell does not host them, only reports into them.
- **Maven publishing**: the modules are consumed in-repo via `project(":island-*")`. Publishing
  coordinates are a follow-up.

## Verification gates

Every phase in the extraction landed with:

- `./gradlew :island-*:compileKotlin :island-*:compileTestKotlin` (module-by-module)
- `./gradlew :island-*:test :gui:test :filepicker:test` (per-layer + consumer regression)
- `grep -r '^import org\.blaze' island-*/src/main/**/*.kt` -> zero hits
- `./gradlew :gui:uiScreenshots` (visual regression, harness lives in `gui`)

The known `OsIntegrationCleanupTest` Windows filesystem-attribute flake is unrelated to this
refactor and predates it.
