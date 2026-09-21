package org.blaze.resolver.api

/** Result of resolving a hosted link into something the download engine can fetch. */
data class ResolvedLink(
    /** A directly downloadable URL (http/https). */
    val directUrl: String,
    /** Suggested file name, if the handler could extract one. */
    val fileName: String? = null,
    /** Suggested total size in bytes, if known. */
    val sizeBytes: Long? = null,
    /** Extra request headers (e.g. a required Referer) to use when downloading. */
    val headers: Map<String, String> = emptyMap()
)