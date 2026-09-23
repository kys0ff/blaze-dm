package org.blaze.osintegration.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Detects the jpackage app-image layout (`<app>/bin/<launcher>` above an `<app>/lib/app` tree).
 *
 * OS registrations (protocol handlers, desktop entries) only ever make sense pointing at the
 * *installed* launcher, never at `java ...` from an IDE run - registering the dev JVM as the
 * `magnet:` handler would be worse than registering nothing.
 */
object PackagedLayout {

    /** The packaged launcher executable, or null when running unpackaged (gradle run / IDE). */
    fun packagedLauncher(command: String? = ProcessHandle.current().info().command().orElse(null)): Path? {
        if (command.isNullOrBlank()) return null
        val executable = runCatching { Paths.get(command) }.getOrNull() ?: return null
        val binDir = executable.parent ?: return null
        if (binDir.fileName.toString() != "bin") return null
        if (!Files.isRegularFile(executable)) return null
        val appDir = binDir.parent ?: return null
        // jpackage ships the application jars in <app>/lib/app; this marker keeps unrelated
        // bin/lib pairs (e.g. /usr/bin/java) from looking like a launch image.
        if (!Files.isDirectory(appDir.resolve("lib").resolve("app"))) return null
        return executable
    }
}
