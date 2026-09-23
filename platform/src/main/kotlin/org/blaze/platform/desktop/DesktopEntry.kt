package org.blaze.platform.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A read-side view over an XDG `.desktop` file, limited to the keys Blaze itself writes and has to
 * reason about: `Exec` (is the program it launches still there?), `Icon` (is the pinned path still
 * alive?) and `StartupWMClass` (is this entry *ours*?).
 *
 * Deliberately not a full desktop-entry parser: one key per line, unknown keys preserved verbatim so
 * a repair rewrite can never eat something the packaging tooling or the user added, and no locale
 * lists/field-code expansion - the values are only ever inspected, never executed.
 */
class DesktopEntry private constructor(private val lines: List<String>) {

    /** Raw value of [key] (first occurrence), trimmed; null when the key is absent. */
    fun key(key: String): String? = lines
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()

    /** The program the entry launches: quotes stripped, field codes (`%F`, `%u`, ...) dropped. */
    fun executable(): Path? = key("Exec")?.let(::executableOf)

    /** The `Icon` target as a path; null for a themed name (no `/`) or when the key is absent. */
    fun iconPath(): Path? = key("Icon")?.takeIf { it.contains('/') }?.let(::pathOf)

    /** The window class the desktop uses to match a running window to this entry. */
    val startupWmClass: String? get() = key("StartupWMClass")

    /** A copy whose `Icon=` points at [icon] (appended after the last key when there is none). */
    fun withIcon(icon: String): DesktopEntry {
        val line = "Icon=$icon"
        val existing = lines.indexOfFirst { it.startsWith("Icon=") }
        val updated = lines.toMutableList()
        if (existing >= 0) updated[existing] = line else updated.add(lines.indexOfLast { it.isNotBlank() } + 1, line)
        return DesktopEntry(updated)
    }

    /** The file content, always newline-terminated as the desktop-entry spec expects. */
    fun content(): String = lines.joinToString("\n").trimEnd() + "\n"

    override fun toString(): String = content()

    companion object {
        private val EPHEMERAL_ROOTS: List<String> = listOf(
            System.getProperty("java.io.tmpdir") ?: "/tmp",
            "/tmp",
            "/var/tmp",
            "/run/user",
        ).map { it.replace('\\', '/').removeSuffix("/") + "/" }

        /**
         * Sub-paths that are *always* transient: libfuse/AppImage mount points (a jpackage AppImage
         * launcher legitimately lives there), macOS per-user temp roots and removable media.
         */
        private val EPHEMERAL_MARKERS = listOf("/.mount_", "/private/var/folders/", "/Volumes/", "/media/", "/mnt/")

        fun parse(content: String): DesktopEntry = DesktopEntry(content.lines())

        /** Reads an entry from disk; null when it is missing or unreadable (so a cleanup pass no-ops). */
        fun read(file: Path): DesktopEntry? = runCatching { parse(Files.readString(file)) }.getOrNull()

        /**
         * Pulls the executable out of an `Exec` value; both shapes Blaze writes are handled - the
         * quoted launcher (`Exec="/opt/Blaze/bin/Blaze" %F`) and a bare command. A value with no path
         * separator (`Exec=blaze`, resolved through `PATH`) comes back null: guessing there would let
         * a cleanup delete a working entry.
         */
        fun executableOf(exec: String): Path? {
            val trimmed = exec.trim()
            if (trimmed.isEmpty()) return null
            val token = if (trimmed.startsWith("\"")) {
                val closing = trimmed.indexOf('"', startIndex = 1)
                if (closing < 0) trimmed.substring(1) else trimmed.substring(1, closing)
            } else {
                trimmed.substringBefore(' ')
            }
            if (!token.contains('/') && !token.contains('\\')) return null
            return pathOf(token)
        }

        /**
         * True when [path] sits under an ephemeral root, i.e. somewhere a *stable* install never lives
         * and whose disappearance proves nothing about an uninstall. An unresolvable path counts as
         * ephemeral, which keeps every "this registration is dead" verdict conservative.
         */
        fun isEphemeral(path: Path): Boolean {
            val normalized = runCatching { path.toAbsolutePath().normalize().toString() }
                .getOrElse { return true }
                .replace('\\', '/')
            return EPHEMERAL_MARKERS.any { normalized.contains(it) } ||
                EPHEMERAL_ROOTS.any { normalized.startsWith(it) }
        }

        private fun pathOf(raw: String): Path? =
            runCatching { Paths.get(raw.trim().removeSurrounding("\"")) }.getOrNull()?.takeIf { it.toString().isNotBlank() }
    }
}
