package org.blaze.resolver.core

import org.blaze.resolver.api.LinkResolver
import java.nio.file.Path

/** Where a handler came from. */
enum class ResolverSource { BUILTIN, PLUGIN }

/** A [LinkResolver] together with the provenance the UI needs. */
data class LoadedResolver(
    val resolver: LinkResolver,
    val source: ResolverSource,
    /** Path to the jar this came from, or null for built-ins. */
    val pluginPath: Path? = null
)
