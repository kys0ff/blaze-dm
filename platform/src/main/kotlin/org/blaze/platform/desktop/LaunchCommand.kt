package org.blaze.platform.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Judgments about the *command* a desktop registration launches, shared by everything that writes or
 * keeps a login item: only a real program is safe to hand to the desktop.
 *
 * The failure this prevents is concrete: `ProcessHandle.current().info().command()` reports the bare
 * JVM while running from an IDE or `gradle run`, so a run-at-startup toggle flipped during debugging
 * would register `/path/to/jbr/bin/java` as a login item. It launches with no classpath, no module and
 * no window - an error dialog (or a silent no-op) at every sign-in, from a session the user long
 * finished. Registering the dev JVM is worse than registering nothing, so it is refused.
 */
object LaunchCommand {

    /** Programs that can never be a login item on their own: they need an argument we cannot supply. */
    private val INTERPRETERS = setOf(
        "java", "java.exe", "javaw", "javaw.exe", "sh", "bash", "dash", "zsh",
        "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh",
    )

    /** Text that only appears in a hand-built development launch (`java -cp ... org.blaze.MainKt`). */
    private val DEV_MARKERS = listOf("-classpath", "-cp ", "--class-path", "--module-path", "--add-opens", "mainkt")

    /** The program an `Exec`/`Run` value names, as a path; null for a `PATH`-relative command. */
    fun executableOf(command: String): Path? = DesktopEntry.executableOf(command)

    /**
     * Whether [command] is something a desktop session can launch by itself: its program is neither an
     * interpreter nor a development launch dressed up as one. A `PATH`-relative command (`blaze`) is
     * accepted - packaging legitimately installs a bare launcher into `/usr/bin`.
     */
    fun isStandaloneExecutable(command: String): Boolean {
        val normalized = command.trim()
        if (normalized.isEmpty()) return false
        if (DEV_MARKERS.any { normalized.lowercase().contains(it) }) return false
        val program = programOf(normalized)?.substringAfterLast('\\')?.substringAfterLast('/')?.lowercase()
        return program != null && program !in INTERPRETERS
    }

    /**
     * Whether [command] names a stable install location whose program is gone - the signature of an
     * uninstall or a moved install. Ephemeral roots (temp dirs, AppImage mounts) never qualify: their
     * contents are *expected* to disappear between runs, so deleting such a registration would break a
     * working setup. A `PATH`-relative command is never "missing" either: resolving it here would mean
     * guessing at the session's `PATH`.
     */
    fun isMissingInstall(command: String): Boolean {
        val executable = executableOf(command) ?: return false
        if (!executable.isAbsolute || DesktopEntry.isEphemeral(executable)) return false
        return !Files.exists(executable)
    }

    /** The first token of a launch command, honoring the quoted form `Exec="path with space" %F`. */
    private fun programOf(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        val token = if (trimmed.startsWith("\"")) {
            val closing = trimmed.indexOf('"', startIndex = 1)
            if (closing < 0) trimmed.substring(1) else trimmed.substring(1, closing)
        } else {
            trimmed.substringBefore(' ')
        }
        return token.takeIf { it.isNotBlank() }
    }
}
