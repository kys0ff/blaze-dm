package org.blaze.engine.api

/**
 * The name Blaze gives a download when the one it asked for is already taken: the original plus a
 * numbered marker - `Big.Buck` becomes `Big.Buck (1)`, and a file keeps its extension, `movie.mkv`
 * becomes `movie (1).mkv`, because an extension describes the bytes rather than the copy.
 *
 * The marker is deliberately reversible. A download that had to step aside can then be recognized
 * again from its own name, which matters wherever the name is decided twice: a torrent's folder is
 * named before its metainfo arrives, and the metainfo must not hand the copy back the name that
 * collided in the first place.
 */
object CopyName {

    /** [name] carrying the marker for copy [attempt], which counts from 1. */
    fun of(name: String, attempt: Int): String = "$name ($attempt)"

    /**
     * The copy number [candidate] carries for [name], or null when it is not a numbered copy of it.
     *
     * Only whole-name copies are recognized: [name] is expected without an extension, which is the
     * shape a torrent's folder has. Anything that does not read as a positive count - `Big.Buck ()`,
     * `Big.Buck (zero)`, `Big.Buck (-1)` - is just a name somebody chose.
     */
    fun attemptOf(candidate: String, name: String): Int? {
        val prefix = "$name ("
        if (candidate == name || !candidate.startsWith(prefix) || !candidate.endsWith(")")) return null
        val digits = candidate.substring(prefix.length, candidate.length - 1)
        return digits.toIntOrNull()?.takeIf { it > 0 && digits.all(Char::isDigit) }
    }
}
