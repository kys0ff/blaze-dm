package org.blaze.resolver.api

/**
 * Contract that a link handler ("extension") implements to turn a page/short link that
 * can't be downloaded directly (e.g. a MediaFire share page) into a direct file URL.
 *
 * Extensions are discovered in two ways:
 *  - Built-in: bundled with the app and registered directly in the DI graph.
 *  - Plugin: compiled against this module and dropped into the extensions directory as a
 *    jar providing a `META-INF/services/org.blaze.resolver.api.LinkResolver` entry so they
 *    can be loaded through [java.util.ServiceLoader].
 *
 * [resolve] is intentionally blocking: the registry always invokes it on an IO dispatcher,
 * which keeps third-party jars free of a Kotlin-coroutines dependency.
 */
interface LinkResolver {
    /** Stable, unique identifier. Used to persist enable/disable state. */
    val id: String

    /** Human-readable name shown in the picker and settings (e.g. "MediaFire"). */
    val displayName: String

    /** Short one-line description of what this handler does. */
    val description: String

    /**
     * Optional icon shown next to this handler in the UI (add-download picker, settings
     * list, inline auto-resolve notice). The path is interpreted by
     * [java.lang.Class.getResourceAsStream], so it must start with `/` for an absolute
     * lookup inside the resolver's own jar / classpath — e.g. `/icons/mediafire.png`.
     * Return null (the default) to fall back to the built-in placeholder.
     *
     * The registry loads the resource via the resolver's own class loader, so plugin
     * jars can ship their icon without any extra wiring.
     *
     * When both [iconResourcePath] and [iconBase64] are provided, [iconBase64] wins —
     * the inline string is the more explicit choice.
     */
    val iconResourcePath: String? get() = null

    /**
     * Optional inline icon for handlers that would rather not ship a resource file (e.g.
     * single-class test plugins, or authors who prefer to keep everything in code). The
     * value is the raw Base64 body of any image format Compose Desktop can decode (PNG is
     * recommended; SVG is not). Both standard and MIME alphabets are accepted, so line
     * breaks from a copy/pasted constant are fine.
     *
     * A `data:image/...;base64,` prefix is stripped if present, which lets authors paste
     * a data URL straight from a browser tool. If decoding fails (malformed Base64, empty
     * string, unreadable image), the registry silently falls back to [iconResourcePath]
     * and then to the built-in placeholder — a bad icon never breaks the resolver.
     */
    val iconBase64: String? get() = null

    /** True when this handler believes it can resolve [url] (host/path check). */
    fun canResolve(url: String): Boolean

    /**
     * Resolve [url] into a direct download link.
     *
     * Implementations should throw with a readable message on failure; the caller surfaces
     * it as a user-facing error.
     */
    fun resolve(url: String): ResolvedLink
}