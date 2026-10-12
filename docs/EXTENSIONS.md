# Blaze Extensions — Authoring Themes & Link Handlers

**Audience:** anyone who wants to change how Blaze looks, or teach it a new file-host, **without**
forking the app. · **Status:** stable · **Requires:** JDK 21, a Kotlin JVM project.

Blaze has exactly two user-facing extension points, each a plain Java `interface` discovered with
`java.util.ServiceLoader`:

| Extension        | Contract module  | Interface                                  | Dropped into                | Managed from                 |
|------------------|------------------|--------------------------------------------|-----------------------------|------------------------------|
| **Colour theme** | `:theming`       | `org.blaze.theming.api.BlazeThemeProvider` | `~/.blaze/themes/*.jar`     | Settings → **Themes**        |
| **Link handler** | `:link-resolver` | `org.blaze.resolver.api.LinkResolver`      | `~/.blaze/extensions/*.jar` | Extensions stripe · Settings → **Link Handlers** |

Both are **in-process, untrusted-but-plain-JVM code** with no sandbox process and no activation API.
You build a jar, the user installs it, Blaze loads it — with one asymmetry: a link-handler jar must
ship a **manifest** (`META-INF/blaze/resolver.json`) declaring its identity, icons and URL claims,
because Blaze reads it *before* any of the jar's code runs (§4.2). A handler jar without a valid
manifest is skipped and reported, never loaded. Theme jars remain pure ServiceLoader affairs.

---

## 1. Ground rules (identical for both kinds)

1. **Depend only on the contract module.** `:theming` and `:link-resolver` expose no Compose, Jewel,
   coroutines, Ktor or engine types in their public API. Compile against them with `compileOnly`
   semantics — Blaze already ships them, so **do not** bundle them (or Kotlin stdlib-adjacent UI
   deps) inside your jar.
2. **No coroutines in your API surface.** Handler `resolve()` and theme `provide()` are ordinary
   blocking functions. The app moves `resolve()` onto an IO dispatcher for you.
3. **Target JVM 21**, matching the app toolchain.
4. **Ids are permanent.** The id is the persistence key — a disabled handler (its manifest `id`) and
   the selected theme (`BlazeThemeProvider.id`) are stored by it. Use lower-case, ASCII, stable,
   namespaced-ish names (`acme-share`, `acme-night`); a handler id must match `[a-z0-9][a-z0-9_-]*`.
   Changing it silently orphans the user's saved preference and turns "remove" into a stale jar.
5. **Never assume you're the only provider.** Multiple handlers can claim the same URL; the user picks.
6. **A crash inside your jar cannot crash the app — but it will disable your feature.** Every call into
   plugin code is guarded (§5). Design for "return nothing useful" rather than "throw at init".

### Directory layout on disk

```
~/.blaze/
├── themes/            ← theme jars            (config: themes.json   → extensionDir, selectedThemeId)
├── extensions/        ← link-handler jars     (config: resolvers.json → extensionDir, disabledHandlers, alwaysAskHandler, requestTimeoutSeconds, handlerOptions{})
├── settings.json      ← engine settings
├── app.json           ← shell settings
└── …
```

Both directories are re-scanned on startup, on **Reload** in Settings, and immediately after an
**Install**/**Remove**. Jars are loaded in **filename order**, so `01-foo.jar` is registered before
`02-bar.jar` — use that if you ship more than one provider and care about the default pick order.

---

## 2. Project setup

A standalone Gradle project (it does **not** need to be inside the Blaze repo):

```kotlin
// build.gradle.kts
plugins { kotlin("jvm") version /* same as Blaze's Kotlin */ }

repositories { mavenCentral() }

dependencies {
    // The contract you implement. Provided at runtime by Blaze.
    compileOnly(files("/path/to/Blaze/theming/build/libs/theming-1.0.0.jar"))
    // …or, for a link handler:
    // compileOnly(files("/path/to/Blaze/link-resolver/build/libs/link-resolver-1.0.0.jar"))
}

kotlin { jvmToolchain(21) }

tasks.jar {
    // Do NOT embed the contract module or Kotlin UI deps — see §1.1.
    manifest { /* nothing special required (Blaze's resolver manifest is a *resource*, §4.2) */ }
}
```

> Publish `:theming` / `:link-resolver` once (`./gradlew :theming:jar`) and hand authors the jar, or
> have them include Blaze as an included build. The API surface is small and versioned with the app.

Then register your implementation the standard ServiceLoader way — a text file whose **name is the
fully-qualified interface** and whose **content is your class name**:

```
src/main/resources/META-INF/services/org.blaze.theming.api.BlazeThemeProvider
```
```
com.example.blaze.MidnightSlateTheme
```

(or `META-INF/services/org.blaze.resolver.api.LinkResolver` for a handler). One class name per line.

A **link-handler jar additionally requires** a JSON manifest at
`src/main/resources/META-INF/blaze/resolver.json` — the full schema is §4.2. Without it the jar is
refused before its code ever runs. The service file itself is optional for handlers: listing a
`mainClass` in the manifest instantiates that class directly instead. One manifest describes one
handler; with several service providers listed, only the first is loaded.

---

## 3. Writing a color theme

### 3.1 The contract

```kotlin
interface BlazeThemeProvider {
    val id: String                 // stable persistence key
    val displayName: String        // shown in the picker
    val description: String        // one line, shown under the name
    val author: String   get() = ""                 // optional credit, shown as a byline
    val version: String  get() = ""                 // optional, informational only
    val supportedModes: Set<ThemeAppearanceMode> get() = ThemeAppearanceMode.ALL
    fun provide(dark: Boolean): ThemePalette
}
```

`provide(dark)` is called whenever the app resolves its colors — on theme switch, on a light/dark
change, and when one of a theme's saved options is edited. It must be **cheap and side effect free**:
no I/O, no network, no allocation-heavy computation, and it may be called on the UI thread.

**Blaze renders exactly one design system** — the JetBrains New UI **Islands** shell (a neutral window
*frame* with rounded *islands* floating on it). A theme is always an **overlay of tokens on top of the
stock shell**, never a replacement of it: you can repaint the accent, the surfaces, the chrome — but
the island/gap structure stays. Every unresolved token falls back, so a theme can never leave part of
the UI unpainted.

`supportedModes` declares which of light/dark the theme actually paints. A dark-only theme that
returns `setOf(DARK)` is offered for dark mode only; when the user lands on light mode the app shows
the built-in default instead of stretching dark tokens over light chrome.

### 3.2 The palette

```kotlin
data class ThemePalette(
    val accentArgb: Long? = null,          // selection, progress bars, links, focused states
    val successArgb: Long? = null,         // completed downloads, connected status
    val warningArgb: Long? = null,         // paused / seeding
    val errorArgb: Long? = null,           // failures, destructive actions
    val hoverArgb: Long? = null,           // row hover overlay
    val panelBackgroundArgb: Long? = null, // sidebar, headers, dialog surfaces
    val borderArgb: Long? = null,          // hairline borders
    val infoTextArgb: Long? = null,        // secondary / informational text
    val islands: IslandsPalette = IslandsPalette.Empty, // the shell's own chrome tokens
)
```

Every field is **optional and independently meaningful**:

* `null` = "don't override" — the built-in value is kept. A one-line accent theme is legitimate.
* `ThemePalette.Empty` = override nothing; that's exactly what the built-in `default` theme is.
* Values are packed **`0xAARRGGBB`**. Always include the alpha byte: `0xFF2E6FD8` is opaque blue,
  while `0x2E6FD8` is *fully transparent* 6F-D8-blue and will look like a bug, not a color.
* Light and dark are two separate calls. A theme that returns the same palette for both will look
  wrong on one of them — dark surfaces in a light window read as a rendering glitch.

Resolution is a strict **overlay chain**, so any subset of tokens composes cleanly:

```
user option colors  →  selected theme  →  built-in default  →  stock Islands tokens
```

Each step only fills the tokens the step above left `null`; the last step guarantees a complete,
renderable palette.

### 3.3 Islands chrome (`IslandsPalette`)

The New UI shell's own vocabulary lives beside the Jewel-mapped colors, because Jewel only knows
"panel background" while Islands needs the whole chrome ramp:

```kotlin
data class IslandsPalette(
    val frameArgb: Long? = null,             // window chrome behind islands (bar, stripe, gaps)
    val surfaceArgb: Long? = null,           // island body (the floating panel surface)
    val stripeSelectedArgb: Long? = null,    // selected tool-window stripe button
    val stripeHoverArgb: Long? = null,       // stripe hover (a translucent wash)
    val borderArgb: Long? = null,            // hairline that rims an island
    val ambientAccentArgb: Long? = null,     // color of the top-left ambient glow (null = follow accent)
    val ambientIntensityPercent: Int? = null,// glow peak, 0..100 (null = the shell's measured default)
)
```

Leave a field `null` to keep the stock value — `IslandsDefaults.forMode(dark)` exposes the exact
numbers the app ships with, so you can build a partial override ("keep the frame, restyle only the
surface") without hard-coding literals you can't otherwise observe.

> **Untrusted & validated.** A plugin jar's colors are run through a sanitizer before they render:
> out-of-range longs and fully-transparent values (that aren't a deliberately translucent token
> like hover/border) are dropped to "unset", so the fallback shows through instead of painting
> garbage. A throwing plugin is contained the same way and simply contributes nothing.

### 3.4 Complete example

```kotlin
package com.example.blaze

import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.IslandsPalette
import org.blaze.theming.api.ThemePalette

class MidnightSlateTheme : BlazeThemeProvider {
    override val id: String = "midnight-slate"
    override val displayName: String = "Midnight Slate"
    override val description: String = "Indigo accent over cool graphite surfaces"
    override val author: String = "Ada Example"
    override val version: String = "1.0.0"

    override fun provide(dark: Boolean): ThemePalette = if (dark) {
        ThemePalette(
            accentArgb = 0xFF7C6CFF,
            successArgb = 0xFF5FB865,
            warningArgb = 0xFFE6B450,
            errorArgb = 0xFFE06C75,
            panelBackgroundArgb = 0xFF23252C,
            borderArgb = 0xFF383C46,
            infoTextArgb = 0xFF8A93A0,
            islands = IslandsPalette(
                frameArgb = 0xFF1B1D22,     // a touch deeper than the stock frame
                surfaceArgb = 0xFF23252C,
            ),
        )
    } else {
        ThemePalette(
            accentArgb = 0xFF4B3FD6,
            panelBackgroundArgb = 0xFFF2F3F8,
            borderArgb = 0xFFD6D9E4,
            infoTextArgb = 0xFF5A6070,
            // success/warning/error/hover left null → app defaults are kept
        )
    }
}
```

### 3.5 Ship, install, verify

1. `./gradlew jar` → `midnight-slate-1.0.0.jar`.
2. Settings → **Themes** → **Install** and pick the jar (it is copied into `~/.blaze/themes/`),
   or copy the file there yourself and press **Reload**.
3. Select it, press **Apply** to preview it live, **OK** to persist. Cancelling reverts to the saved theme.
4. The new theme appears tagged **Extension**, with your `author` / `version` as a byline; built-ins
   can be previewed but never removed.

Tip: while developing, keep a terminal on `tail -f` of Blaze's log — a rejected jar is logged there,
not shown in the UI.

### 3.6 Making a theme configurable (options)

A theme can let the user tune it from **Settings → Themes → Options** by implementing the optional
`ConfigurableBlazeThemeProvider` facet instead of the base interface. It **extends**
`BlazeThemeProvider`, so it is discovered through the very same `META-INF/services` file — no second
service entry, and a plain provider keeps working untouched.

```kotlin
interface ConfigurableBlazeThemeProvider : BlazeThemeProvider {
    val optionDescriptors: List<ThemeOption> get() = emptyList()
    fun applySettings(settings: ThemeConfig) {}        // adopt the merged values (pure assignment)
    fun settings(): ThemeConfig = ThemeConfigDefaults.fromDescriptors(optionDescriptors)
}
```

The facet is deliberately **stateless at render time**: the app reads your `optionDescriptors`, builds
the form, persists the answers itself, and folds them back into the resolved palette — a color option
is applied straight onto its declared slot, so overriding, say, the accent needs **no plugin code in
the render path**. `provide()` is never called with a promise that the plugin remembered an edit; if
you keep internal state, adopt it in `applySettings()`.

Descriptors are plain, serializable data — never functions — so the UI can render a form without
running your code. Build them with the helpers in `org.blaze.theming.api`:

```kotlin
import org.blaze.theming.api.*

class AuraTheme : ConfigurableBlazeThemeProvider {
    override val id = "aura"
    override val displayName = "Aura"
    override val description = "Tunable accent and glow"

    override val optionDescriptors = listOf(
        // A color the app writes onto ThemePalette.accentArgb for you:
        themeColorOption("accent", "Accent color", applyTo = ThemeColorTarget.ACCENT, defaultArgb = 0xFF7C6CFF),
        // A color for the Islands frame surface:
        themeColorOption("surface", "Island surface", applyTo = ThemeColorTarget.ISLANDS_SURFACE),
        // The peak strength of the top-left ambient glow (0..100), applied onto the Islands chrome:
        themeAmbientIntensityOption("glow", "Glow strength", defaultPercent = 15),
        // Anything else the plugin reads itself via applySettings():
        themeSelectOption("density", "List density", choices = listOf("Compact", "Comfortable"), default = "Comfortable"),
    )

    override fun provide(dark: Boolean): ThemePalette = ThemePalette(/* base look */) 
}
```

`ThemeOptionType` covers **Text, Boolean, Integer(min..max), Select(choices) and Color**. A color
option's `colorTarget` (a `ThemeColorTarget`) names which palette slot the app overrides with the
user's value — the closed set spans the semantic colors (`ACCENT`, `SUCCESS`, …) and the Islands chrome
(`ISLANDS_FRAME`, `ISLANDS_SURFACE`, `ISLANDS_AMBIENT_ACCENT`, …). An option flagged
`ambientIntensityKey` (what `themeAmbientIntensityOption` produces) drives the glow strength, clamped
to `0..100`. Values the user hasn't touched fall back to each descriptor's `defaultValue`; the answers
persist in `themes.json` keyed by theme id, so an untouched theme reads its declared defaults.

Only declare `optionDescriptors` you actually honor — a descriptor the app can't map to a rendered
token is dead space in the form, and a non-color option must be consumed in `applySettings` /
`provide` for it to have any effect.

---

## 4. Writing a link handler

A handler turns a *page* link that can't be downloaded directly (a host's share page) into a direct
file URL plus its metadata.

### 4.1 The contract

The interface is **pure behavior** — names, icons and URL claims are declared declaratively in the
manifest (§4.2), never in code:

```kotlin
interface LinkResolver {
    fun canResolve(url: String): Boolean
    fun resolve(url: String): ResolvedLink
}
```

```kotlin
data class ResolvedLink(
    val directUrl: String,                       // http/https, directly fetchable
    val fileName: String? = null,                // if you could work it out
    val sizeBytes: Long? = null,                 // if known → progress/ETA up front
    val headers: Map<String, String> = emptyMap() // e.g. a required Referer
)
```

### 4.2 The manifest — required

Every handler jar ships `META-INF/blaze/resolver.json` (put it in
`src/main/resources/META-INF/blaze/resolver.json`). Blaze reads it **straight from the zip, without
opening a class loader or executing a line of your code** — that is what lets the picker, the
Extensions panel and the install-consent prompt describe a jar before it is ever trusted. A jar whose
manifest is missing, malformed or on a schema newer than Blaze understands is **skipped and
reported**, never loaded.

```json
{
  "manifestVersion": 1,
  "id": "acme-share",
  "displayName": "Acme Share",
  "description": "Resolves acme.example share pages into a direct link",
  "version": "1.0.0",
  "author": "Ada Example",
  "homepage": "https://acme.example/handler",
  "license": "MIT",
  "mainClass": "com.example.blaze.AcmeShareResolver",
  "icons": { "svg": "icons/acme.svg", "png": "icons/acme.png" },
  "urlPatterns": [
    "https://acme.example/*",
    "https://*.acme.example/*"
  ],
  "defaultPriority": 0,
  "minAppVersion": "1.0.0"
}
```

| Field | Required | Default | Meaning |
|-------|----------|---------|---------|
| `manifestVersion` | no | `1` | Schema version; newer than this build understands → the jar is rejected. |
| `id` | **yes** | — | Persistence key, `[a-z0-9][a-z0-9_-]{0,63}`. Settings, enable state and priority are stored under it — choose once, never change. |
| `displayName` | **yes** | — | Shown in the picker, the Extensions panel and the consent prompt. |
| `description` | no | `""` | One line under the name. |
| `version` / `author` | no | `0.0.0` / `""` | Byline ("By … · version …") wherever the handler is listed. |
| `homepage` / `license` / `issuesUrl` | no | — | Reference details offered with the handler. |
| `mainClass` | no | — | FQCN of your `LinkResolver` with a public no-arg constructor. When absent, providers are discovered through `META-INF/services` instead. |
| `icons` | no | `{}` | Icon resource paths inside the jar (§4.3). |
| `urlPatterns` | no | `[]` | Case-insensitive `*` globs over the full URL. Non-empty → **hard pre-gate**: your `canResolve` is never called for a URL none of the patterns claims. Empty → unrestricted. |
| `defaultPriority` | no | `0` | Ordering weight until the user reorders the handler in the Extensions panel. |
| `minAppVersion` | no | — | Lowest Blaze version you support; jars requiring a newer app are skipped with a readable reason. |

Unknown keys are ignored, so adding fields later stays forward compatible. Anything *structurally*
invalid — bad `id`, blank `displayName`, a `..` in an icon path, unparseable JSON, a
`manifestVersion` in the future — rejects the whole jar; there is no partial load. **One manifest
describes one handler:** a jar whose service file lists several providers loads the first and skips
the rest.

### 4.3 Icons & jar layout

`icons` entries are resource paths **relative to the jar root** (a leading `/` is accepted and
normalized away; traversal is rejected). Both **PNG and SVG** are supported — SVG is preferred
whenever it is declared and is rasterized by Blaze at display size — and each format may carry a
dark-theme variant (`pngDark`, `svgDark`) that Blaze selects when the app runs in dark mode. A
declared-but-missing file falls back through the remaining variants and finally to the generic
placeholder; an icon can never crash a row.

```
acme-share-1.0.0.jar
├── META-INF/
│   ├── blaze/resolver.json                          ← required (§4.2)
│   └── services/org.blaze.resolver.api.LinkResolver ← instead of mainClass
├── icons/
│   ├── acme.svg                                     ← declared in "icons"
│   └── acme.png
└── com/example/blaze/AcmeShareResolver.class
```

### 4.4 Rules that make a handler behave well

* **`canResolve` is a router, not a parser.** It runs on the UI thread for every URL that survives
  your manifest's pattern pre-gate (§4.2), so check host/path shape only — no network, no HTML.
  Return `false` fast for anything that isn't yours.
* **`resolve` blocks, and that's fine.** The registry calls it inside `runCatching` on an IO
  dispatcher. Do the HTTP fetch, parse, return. Give your `HttpClient` a real `connectTimeout` — a
  hanging handler holds a user's dialog open.
* **Throw readable messages.** The exception message becomes the user-facing error. Prefer
  `error("Couldn't find #downloadButton on the page")` over a stack trace or an opaque
  `IllegalStateException`. Failure that the user can act on ("file removed", "needs a password")
  should say so.
* **Pass along what the transfer needs.** If the direct URL only works with a `Referer` or a
  `User-Agent`, put it in `ResolvedLink.headers`; Blaze uses it for the actual download.
* **Never invent a `fileName`.** `null` is better than a wrong name — the engine derives one from the
  URL/response when absent. Same for `sizeBytes`.
* **Icons are declared in the manifest (§4.3), not in code.** PNG and SVG both work, dark variants
  are optional, and a missing or unreadable asset falls back to the placeholder — never a crash.
* **Thread-safety:** one instance is created per jar at scan time and reused for every resolve. Keep
  it stateless, or guard your state.

### 4.5 Complete example

The behavior class holds no metadata — the §4.2 manifest is the only place identity lives:

```kotlin
package com.example.blaze

import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class AcmeShareResolver : LinkResolver {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    override fun canResolve(url: String): Boolean =
        runCatching { URI(url.trim()).host?.lowercase() }
            .getOrNull()
            ?.let { it == "acme.example" || it.endsWith(".acme.example") } == true

    override fun resolve(url: String): ResolvedLink {
        val page = url.trim()
        val html = fetch(page)

        val direct = DIRECT_LINK.find(html)?.groupValues?.getOrNull(1)
            ?: error("This Acme page has no download link — the file may have been removed.")

        return ResolvedLink(
            directUrl = direct,
            fileName = direct.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() },
            sizeBytes = SIZE.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull(),
            headers = mapOf("Referer" to page),
        )
    }

    private fun fetch(url: String): String {
        val request = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", "Blaze/AcmeHandler")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Acme returned HTTP ${response.statusCode()}" }
        return response.body()
    }

    private companion object {
        val DIRECT_LINK = Regex("""href="(https://dl\.acme\.example/[^"]+)"""")
        val SIZE = Regex(""""fileSize"\s*:\s*(\d+)""")
    }
}
```

Ship it together with the §4.2 `resolver.json` (`mainClass` pointing at
`com.example.blaze.AcmeShareResolver`, or a
`META-INF/services/org.blaze.resolver.api.LinkResolver` file listing that class instead).

### 4.6 How it's used at runtime

When the user pastes a link, Blaze lists the **enabled** handlers whose manifest `urlPatterns` claim
it (if any are declared — an unclaimed URL never reaches the plugin's `canResolve`) and whose
`canResolve` then returns true, ordered best-first (a user-set **priority** wins, the manifest's
`defaultPriority` applies until the user touches it, display name breaks ties). The top of that list
is the one used silently when "always ask" is off:

* one match → resolved automatically;
* several matches → the user is asked which to use (and "always ask" in Settings → **Link Handlers**
  forces that chooser even for a single match);
* none, and the link names a file (a `.zip`, an `.iso`, a `linux.torrent`) → downloaded directly;
* none, and the link reads as a **page** (a site root, an extension-less route like `/share/abc`, an
  `.html`/`.php`/… URL) → Blaze warns that no handler can take it apart. If the user proceeds, the
  page itself is saved as a plain download.

The last case is why a handler matters: without one, the only thing Blaze can do with a share page is
fetch the page. A handler turns it into the file behind it.

A disabled handler stays loaded; it just stops matching. Handlers are managed from two places: the
**Extensions** tool-window stripe (enable/disable, install/remove, per-handler options and priority —
every edit takes effect **immediately**) and Settings → **Link Handlers** (the buffered draft with
Apply/OK, plus the global always-ask and extensions-folder preferences). Both write the same
`resolvers.json`.

### 4.7 Testing without the app

Both contracts are pure interfaces over `String`/`Long` values — instantiate them directly in a unit
test, no Blaze on the classpath:

```kotlin
class AcmeShareResolverTest {
    @Test fun `claims only acme hosts`() {
        val r = AcmeShareResolver()
        assertTrue(r.canResolve("https://acme.example/s/abc"))
        assertFalse(r.canResolve("https://example.com/file.zip"))
    }
}
```

Keep the HTML fixtures as strings so you can assert on the *error* paths too — the messages you throw
are the messages your users read. Also run your `resolver.json` text through
`org.blaze.resolver.api.ResolverManifest.parse` in a test: a manifest that fails validation costs you
every install silently — the jar is simply skipped, with a log line and no stack trace.

### 4.8 Configurable handlers: options, multiple links & priority

A plain `LinkResolver` is single-link and option-free. To publish user-editable settings, return
several links from one page, or honour the subsystem's HTTP timeout, implement the
`ConfigurableLinkResolver` sub-interface instead. It **extends** `LinkResolver`, so it is discovered
exactly the same way — through the manifest's `mainClass` or the very same
`META-INF/services/org.blaze.resolver.api.LinkResolver` file (no second service entry) — and its
options persist under the manifest's `id`.

```kotlin
interface ConfigurableLinkResolver : LinkResolver {
    val optionDescriptors: List<ResolverOption> get() = emptyList()
    fun settings(): ResolverConfig                = ResolverSettingsDefaults.fromDescriptors(optionDescriptors)
    fun applySettings(settings: ResolverConfig)   {}
    fun resolveAll(url: String, context: ResolverContext): List<ResolvedLink> = listOf(resolve(url))
}
```

Every member has a default, so a configurable handler overrides only what it needs. The registry
calls `resolveAll` for you: it invokes `applySettings(...)` with the handler's **persisted option
values merged over the declared defaults**, then `resolveAll(url, ResolverContext(timeoutSeconds))`.
Your `resolve` is only used for a plain handler, so the two paths never overlap.

**Declare typed options.** `ResolverOption` is a `@Serializable` descriptor the app renders into a
form without running your code. Use the helper constructors; labels/descriptions are plain strings
(you own the wording — extensions have no access to Blaze's translations):

```kotlin
import org.blaze.resolver.api.*

class AcmeShareResolver : ConfigurableLinkResolver {
    override val optionDescriptors = listOf(
        textOption("userAgent", "User-Agent", default = "Blaze/AcmeHandler"),
        integerOption("minFreeKb", "Min free space (KB)", range = 0L..1_048_576L, default = 0L),
        booleanOption("followRedirects", "Follow redirects", default = true),
        selectOption("quality", "Quality", choices = listOf("auto", "1080", "720"), default = "auto"),
    )

    @Volatile private var config = ResolverSettingsDefaults.fromDescriptors(optionDescriptors)
    override fun applySettings(settings: ResolverConfig) { config = settings }

    override fun resolveAll(url: String, context: ResolverContext): List<ResolvedLink> {
        val agent = config.value("userAgent", "Blaze/AcmeHandler")
        val timeout = context.requestTimeoutSeconds   // seconds, from the subsystem ResolverContext
        // ...fetch with agent/timeout, return ONE link:
        return listOf(resolve(url))
    }
    // resolve(url) still satisfies the LinkResolver contract for the plain single-link fallback.
}
```

`ResolverConfig` reads back type-safely: `value(key, default)`, `booleanValue`, `intValue(key,
default, range)` (clamped), and `selectValue(key, choices, default)`. Values are stored in the
handler's slice of `resolvers.json` (`handlerOptions[id].optionValues`); a key the user never touched
falls back to the descriptor's `defaultValue`, so shipping a new option never breaks an existing
install.

**Return several links.** A page that offers mirrors or multiple files can return them all from
`resolveAll`. The add-download flow then lets the user tick which to grab — one chosen continues the
normal single-file review, several become a batch. There is no ordering requirement, but put the
best/default candidate first: a handler whose `resolve` fallback the plain path uses returns `listOf(resolve(url))`.

**Keep it coroutine-free.** `optionDescriptors` must be a cheap, stable list of data — it is read on
the UI thread to build the form. Do no network or disk work in the descriptors or in
`applySettings`; treat them as pure get/set.

---

## 5. Failure & sandboxing model

Plugin code is untrusted, so Blaze wraps every entry point (`SafeThemeProvider`, `SafeLinkResolver`):

| What fails                              | What the user sees                                                           |
|-----------------------------------------|------------------------------------------------------------------------------|
| Jar unreadable / not a zip              | Skipped at scan; logged. Nothing in the list.                                |
| Handler manifest missing / malformed / schema too new | Jar **never loads** — skipped with a readable reason, logged and reported as a rejected jar. |
| `minAppVersion` above the running Blaze | Same: skipped with "requires Blaze >= X"; no plugin code ran.                |
| `mainClass` missing / not an SPI / its constructor throws | The jar contributes nothing (logged); other jars keep loading.         |
| `canResolve` throws                     | Treated as `false` — the handler simply never matches.                       |
| `optionDescriptors`/`settings()` throws | Falls back to an empty form / default config — the config gear just shows nothing to edit. |
| `applySettings` throws                  | Swallowed; the handler resolves with whatever config it last held.           |
| `provide` throws                        | `ThemePalette.Empty` — the app keeps its stock look.                         |
| `resolve` throws                        | The exception message is surfaced as the download error (not swallowed).     |
| Selected/installed jar deleted          | Theme falls back to `default`; handler disappears on next reload.            |

Consequences worth internalizing:

* **Metadata never executes code.** Names, bylines, icons and the consent prompt's "what does this
  jar claim?" all come from the manifest, read off the zip — an approved prompt already showed the
  handler's `displayName`, author/version byline and claimed `urlPatterns` before a single class
  loaded.
* **`NoClassDefFoundError` is a normal outcome**, not a fatal one — typically you bundled (or failed
  to bundle) a dependency. Version mismatches degrade to a disabled extension, never a crash.
* Because a broken extension is *silent-ish*, always check Settings → Themes / Link Handlers after
  installing: if it isn't listed, the jar wasn't discovered (or its manifest was refused — the reason
  is in the log).
* Uninstalling from Settings deletes the jar. Doing it by hand works too — press **Reload**.
* This is defense-in-depth, **not** a security boundary. Installing a jar runs its code with the
  user's permissions; ship only jars users trust, and never put secrets in a handler's source.

---

## 6. Author checklist

**Theme**
- [ ] `id` chosen once, never reused, never changed.
- [ ] Separate light and dark palettes; alpha byte present in every ARGB literal.
- [ ] Only fields you actually want to override are set.
- [ ] `provide()` pure, fast, no I/O.
- [ ] Contract module `compileOnly`; jar opens, class listed under `META-INF/services`.
- [ ] Verified: appears in Settings → Themes, previews on Apply, survives a restart.

**Link handler**
- [ ] `META-INF/blaze/resolver.json` ships inside the jar and parses — verify it through
      `ResolverManifest.parse` or a `peekManifest`-style read before publishing.
- [ ] Manifest `id` chosen once, never reused, never changed (`[a-z0-9][a-z0-9_-]*`).
- [ ] `urlPatterns` claims every URL shape the handler supports (or is left empty to stay unrestricted).
- [ ] Icons declared as jar-root-relative PNG/SVG paths; dark variants optional, missing assets degrade to the placeholder.
- [ ] `minAppVersion` only set when the handler genuinely needs a future Blaze build.
- [ ] `canResolve` is host/path-only, allocation-light, no network.
- [ ] HTTP client has connect/read timeouts.
- [ ] Every failure path throws a message a user can act on.
- [ ] Required `Referer`/`User-Agent` returned in `ResolvedLink.headers`.
- [ ] `fileName`/`sizeBytes` omitted rather than guessed.
- [ ] Configurable handlers: descriptors are cheap/stable data, values read via `ResolverConfig`, no I/O in `applySettings`.
- [ ] Verified against a real page *and* a page that no longer exists.
