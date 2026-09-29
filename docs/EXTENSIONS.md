# Blaze Extensions — Authoring Themes & Link Handlers

**Audience:** anyone who wants to change how Blaze looks, or teach it a new file-host, **without**
forking the app. · **Status:** stable · **Requires:** JDK 21, a Kotlin JVM project.

Blaze has exactly two user-facing extension points, each a plain Java `interface` discovered with
`java.util.ServiceLoader`:

| Extension        | Contract module  | Interface                                  | Dropped into                | Managed from                 |
|------------------|------------------|--------------------------------------------|-----------------------------|------------------------------|
| **Colour theme** | `:theming`       | `org.blaze.theming.api.BlazeThemeProvider` | `~/.blaze/themes/*.jar`     | Settings → **Themes**        |
| **Link handler** | `:link-resolver` | `org.blaze.resolver.api.LinkResolver`      | `~/.blaze/extensions/*.jar` | Settings → **Link Handlers** |

Both are **in-process, untrusted-but-plain-JVM code**: no manifest metadata, no sandbox process, no
activation API. You build a jar, the user installs it, Blaze loads it.

---

## 1. Ground rules (identical for both kinds)

1. **Depend only on the contract module.** `:theming` and `:link-resolver` expose no Compose, Jewel,
   coroutines, Ktor or engine types in their public API. Compile against them with `compileOnly`
   semantics — Blaze already ships them, so **do not** bundle them (or Kotlin stdlib-adjacent UI
   deps) inside your jar.
2. **No coroutines in your API surface.** Handler `resolve()` and theme `provide()` are ordinary
   blocking functions. The app moves `resolve()` onto an IO dispatcher for you.
3. **Target JVM 21**, matching the app toolchain.
4. **Ids are permanent.** `id` is the persistence key — a disabled handler and the selected theme are
   stored by id. Use lower-case, ASCII, stable, namespaced-ish (`acme-share`, `acme-night`). Changing
   it silently orphans the user's saved preference and turns "remove" into a stale jar.
5. **Never assume you're the only provider.** Multiple handlers can claim the same URL; the user picks.
6. **A crash inside your jar cannot crash the app — but it will disable your feature.** Every call into
   plugin code is guarded (§4). Design for "return nothing useful" rather than "throw at init".

### Directory layout on disk

```
~/.blaze/
├── themes/            ← theme jars            (config: themes.json   → extensionDir, selectedThemeId)
├── extensions/        ← link-handler jars     (config: resolvers.json → extensionDir, disabledHandlers, alwaysAskHandler)
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
    manifest { /* nothing special required */ }
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

(or `META-INF/services/org.blaze.resolver.api.LinkResolver` for a handler). One class name per line;
multiple providers in one jar are supported and each is discovered independently.

---

## 3. Writing a color theme

### 3.1 The contract

```kotlin
interface BlazeThemeProvider {
    val id: String                 // stable persistence key
    val displayName: String        // shown in the picker
    val description: String        // one line, shown under the name
    fun provide(dark: Boolean): ThemePalette
}
```

`provide(dark)` is called whenever the app resolves its colors — i.e. on theme switch and on
light/dark change. It must be **cheap and side effect free**: no I/O, no network, no allocation-heavy
computation, and it may be called on the UI thread.

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
)
```

Every field is **optional and independently meaningful**:

* `null` = "don't override" — the built-in value is kept. A one-line accent theme is legitimate.
* `ThemePalette.Empty` = override nothing; that's exactly what the built-in `default` theme is.
* Values are packed **`0xAARRGGBB`**. Always include the alpha byte: `0xFF2E6FD8` is opaque blue,
  while `0x2E6FD8` is *fully transparent* 6F-D8-blue and will look like a bug, not a color.
* Light and dark are two separate calls. A theme that returns the same palette for both will look
  wrong on one of them — dark surfaces in a light window read as a rendering glitch.

Fallback is per-field, so partial themes compose cleanly:

```
effective(color) = selectedTheme.provide(dark).color ?: defaultTheme.provide(dark).color
```

### 3.3 Complete example

```kotlin
package com.example.blaze

import org.blaze.theming.api.BlazeThemeProvider
import org.blaze.theming.api.ThemePalette

class MidnightSlateTheme : BlazeThemeProvider {
    override val id: String = "midnight-slate"
    override val displayName: String = "Midnight Slate"
    override val description: String = "Indigo accent over cool graphite surfaces"

    override fun provide(dark: Boolean): ThemePalette = if (dark) {
        ThemePalette(
            accentArgb = 0xFF7C6CFF,
            successArgb = 0xFF5FB865,
            warningArgb = 0xFFE6B450,
            errorArgb = 0xFFE06C75,
            panelBackgroundArgb = 0xFF23252C,
            borderArgb = 0xFF383C46,
            infoTextArgb = 0xFF8A93A0,
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

### 3.4 Ship, install, verify

1. `./gradlew jar` → `midnight-slate-1.0.0.jar`.
2. Settings → **Themes** → **Install** and pick the jar (it is copied into `~/.blaze/themes/`),
   or copy the file there yourself and press **Reload**.
3. Select it, press **Apply** to preview it live, **OK** to persist. Cancelling reverts to the saved theme.
4. The new theme appears tagged **Extension**; built-ins can be previewed but never removed.

Tip: while developing, keep a terminal on `tail -f` of Blaze's log — a rejected jar is logged there,
not shown in the UI.

---

## 4. Writing a link handler

A handler turns a *page* link that can't be downloaded directly (a host's share page) into a direct
file URL plus its metadata.

### 4.1 The contract

```kotlin
interface LinkResolver {
    val id: String
    val displayName: String
    val description: String
    val iconResourcePath: String? get() = null   // "/icons/acme.png", read from YOUR jar
    val iconBase64: String?        get() = null   // raw Base64 PNG body (or a data: URL)
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

### 4.2 Rules that make a handler behave well

* **`canResolve` is a router, not a parser.** It runs on the UI thread for every candidate URL, so
  check host/path shape only — no network, no HTML. Return `false` fast for anything that isn't yours.
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
* **Icons:** `iconResourcePath` is resolved through **your own class loader** (`javaClass.getResourceAsStream`),
  so the PNG can live inside your jar with no extra wiring. If you provide both, **`iconBase64` wins**.
  A malformed Base64 or a missing resource falls back to the placeholder — never a crash — and SVG is
  not supported. Omit both to get the built-in placeholder.
* **Thread-safety:** one instance is created per jar at scan time and reused for every resolve. Keep
  it stateless, or guard your state.

### 4.3 Complete example

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
    override val id: String = "acme-share"
    override val displayName: String = "Acme Share"
    override val description: String = "Resolves acme.example share pages into a direct link"
    override val iconResourcePath: String? = "/icons/acme.png"

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

Registered via `META-INF/services/org.blaze.resolver.api.LinkResolver`.

### 4.4 How it's used at runtime

When the user pastes a link, Blaze lists the **enabled** handlers whose `canResolve` returns true:

* one match → resolved automatically;
* several matches → the user is asked which to use (and "always ask" in Settings → **Link Handlers**
  forces that chooser even for a single match);
* none, and the link names a file (a `.zip`, an `.iso`, a `linux.torrent`) → downloaded directly;
* none, and the link reads as a **page** (a site root, an extension-less route like `/share/abc`, an
  `.html`/`.php`/… URL) → Blaze warns that no handler can take it apart. If the user proceeds, the
  page itself is saved as a plain download.

The last case is why a handler matters: without one, the only thing Blaze can do with a share page is
fetch the page. A handler turns it into the file behind it.

A disabled handler stays loaded; it just stops matching. Enable/disable is buffered with the rest of
the Settings draft and written to `resolvers.json` on OK.

### 4.5 Testing without the app

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
are the messages your users read.

---

## 5. Failure & sandboxing model

Plugin code is untrusted, so Blaze wraps every entry point (`SafeThemeProvider`, `SafeLinkResolver`):

| What fails                              | What the user sees                                                           |
|-----------------------------------------|------------------------------------------------------------------------------|
| Jar unreadable / not a zip              | Skipped at scan; logged. Nothing in the list.                                |
| Constructor throws, missing class       | That provider is skipped; other providers **in the same jar** still load.    |
| `id`/`displayName`/`description` throws | Listed under a synthetic `invalid:<jar-name>` id so it can still be removed. |
| `canResolve` throws                     | Treated as `false` — the handler simply never matches.                       |
| `provide` throws                        | `ThemePalette.Empty` — the app keeps its stock look.                         |
| `resolve` throws                        | The exception message is surfaced as the download error (not swallowed).     |
| Selected/installed jar deleted          | Theme falls back to `default`; handler disappears on next reload.            |

Consequences worth internalizing:

* **`NoClassDefFoundError` is a normal outcome**, not a fatal one — typically you bundled (or failed
  to bundle) a dependency. Version mismatches degrade to a disabled extension, never a crash.
* Because a broken extension is *silent-ish*, always check Settings → Themes / Link Handlers after
  installing: if it isn't listed, the jar wasn't discovered.
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
- [ ] `canResolve` is host/path-only, allocation-light, no network.
- [ ] HTTP client has connect/read timeouts.
- [ ] Every failure path throws a message a user can act on.
- [ ] Required `Referer`/`User-Agent` returned in `ResolvedLink.headers`.
- [ ] `fileName`/`sizeBytes` omitted rather than guessed.
- [ ] Icon is PNG (or left out), read from your own jar.
- [ ] Verified against a real page *and* a page that no longer exists.
