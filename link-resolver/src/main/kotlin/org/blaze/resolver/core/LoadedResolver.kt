package org.blaze.resolver.core

import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolverManifest
import java.nio.file.Path

/** Where a handler came from. */
enum class ResolverSource { BUILTIN, PLUGIN }

/**
 * A [LinkResolver] together with everything the UI needs to present it.
 *
 * [id] and [manifest] come from the handler's [ResolverManifest] — read from jar resources
 * without executing plugin code — so identity and metadata never depend on the (untrusted)
 * implementation behaving.
 */
data class LoadedResolver(
    /** The manifest's stable persistence key. */
    val id: String,
    /** The declarative descriptor this handler was loaded with. */
    val manifest: ResolverManifest,
    val resolver: LinkResolver,
    val source: ResolverSource,
    /** Path to the jar this came from, or null for built-ins. */
    val pluginPath: Path? = null,
)

/**
 * A built-in handler paired with the manifest declared for it in code, exactly like a
 * plugin jar's on-disk one — the UI therefore has a single metadata source for every
 * handler regardless of provenance.
 */
data class BuiltInResolver(
    val resolver: LinkResolver,
    val manifest: ResolverManifest,
)
