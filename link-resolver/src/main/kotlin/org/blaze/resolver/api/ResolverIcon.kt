package org.blaze.resolver.api

/** Wire format of a handler icon's bytes; decided by the manifest path's extension. */
enum class IconFormat { PNG, SVG }

/**
 * A handler's icon as loaded from its own jar/module resources, tagged with the format the
 * renderer must decode it with (Skia for [IconFormat.PNG], the SVG rasterizer for
 * [IconFormat.SVG]). Produced by `LinkResolverRegistry.loadIcon`; the UI falls back to its
 * placeholder when a handler declares no loadable icon.
 */
data class ResolverIcon(
    val bytes: ByteArray,
    val format: IconFormat,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ResolverIcon

        return format == other.format && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * format.hashCode() + bytes.contentHashCode()
}

/** Guesses an encoded icon's [IconFormat] from its magic bytes: XML-ish prologue ⇒ SVG. */
fun detectIconFormat(bytes: ByteArray): IconFormat {
    val head = String(bytes.take(64).toByteArray()).trimStart()
    return if (head.startsWith("<")) IconFormat.SVG else IconFormat.PNG
}
