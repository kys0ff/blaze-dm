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