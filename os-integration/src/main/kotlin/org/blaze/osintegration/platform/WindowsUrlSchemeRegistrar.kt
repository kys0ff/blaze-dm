package org.blaze.osintegration.platform

import org.blaze.osintegration.platform.WindowsUrlSchemeRegistrar.ensureRegistered
import org.blaze.osintegration.platform.WindowsUrlSchemeRegistrar.isOwnHandler
import org.blaze.osintegration.platform.WindowsUrlSchemeRegistrar.runReg
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Keeps `HKCU\Software\Classes\<scheme>` pointed at *this* installed launcher for a custom URL scheme
 * (in practice: `magnet:`).
 *
 * jpackage's installer-level file associations only speak "extension + MIME", so a URL protocol cannot
 * be declared through the MSI; this is the narrow, well-defined gap the runtime fills - the same
 * approach uTorrent-style handlers use. It is *not* ad-hoc: exactly four idempotent `reg add` writes
 * under the current user's classes (no HKLM, no admin), re-asserted on every packaged launch so
 * updates, moves and reinstalls self-heal. Values are passed as discrete process arguments - never
 * through a shell - so paths with spaces, Unicode or `%` characters cannot break out of their field.
 *
 * The command template is `"C:\...\Blaze.exe" "%1"`: the quoted executable plus the full URI as one
 * argv slot, which is exactly what [org.blaze.osintegration.ExternalOpenParser] consumes.
 */
object WindowsUrlSchemeRegistrar {
    private val logger = LoggerFactory.getLogger(WindowsUrlSchemeRegistrar::class.java)
    private const val REG_TIMEOUT_SECONDS = 10L
    private val SAFE_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]{0,31}")

    /** The exact `reg add` argument vectors. Pure and visible for tests; nothing here runs a shell. */
    fun buildCommands(scheme: String, displayName: String, executable: String): List<List<String>> {
        require(scheme.matches(SAFE_SCHEME)) { "refusing to register an unsafe scheme name: $scheme" }
        val root = "HKCU\\Software\\Classes\\$scheme"
        // Windows argv quoting: the value is one field; embedded quotes double up and a path with
        // spaces stays intact because it arrives as a single ProcessBuilder argument, not via a shell.
        val openCommand = "\"${executable.replace("\"", "\"\"")}\" \"%1\""
        return listOf(
            regAdd(root, "/ve", "/d", "URL:$displayName", "/f"),
            regAdd(root, "/v", "URL Protocol", "/d", "", "/f"),
            regAdd("$root\\DefaultIcon", "/ve", "/d", "$executable,0", "/f"),
            regAdd("$root\\shell\\open\\command", "/ve", "/d", openCommand, "/f"),
        )
    }

    /**
     * Registers [scheme] for the current user. False on any failure (non-Windows host, missing reg.exe,
     * refused to write) - callers treat this as best-effort desktop polish, never a startup blocker.
     */
    fun ensureRegistered(scheme: String, displayName: String, executable: String, isWindows: Boolean = isWindowsHost()): Boolean {
        if (!isWindows) return false
        return try {
            buildCommands(scheme, displayName, executable).all { args ->
                runReg(args)
            }
        } catch (e: Exception) { // incl. IllegalArgumentException from an unsafe scheme
            logger.warn("Could not register the {}:// handler for the current user", scheme, e)
            false
        }
    }

    private fun regAdd(vararg rest: String): List<String> = listOf("reg", "add") + rest

    /**
     * The one `reg delete` that drops the whole per-user scheme key (values and sub-keys). Structural
     * arguments again - the scheme is validated before it ever reaches `reg.exe`.
     */
    fun buildUnregisterCommand(scheme: String): List<String> {
        require(scheme.matches(SAFE_SCHEME)) { "refusing to unregister an unsafe scheme name: $scheme" }
        return listOf("reg", "delete", "HKCU\\Software\\Classes\\$scheme", "/f")
    }

    /**
     * The `[HKCU\Software\Classes\<scheme>\shell\open\command]` default value out of `reg query`
     * output, or null when nothing is registered (an error listing reads as "absent" too).
     */
    fun parseOpenCommand(output: String): String? = output.lineSequence()
        .mapNotNull(OPEN_COMMAND_VALUE::find)
        .firstOrNull()
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * Whether a registered open command is Blaze's own to remove: it must launch a program named
     * [executableName] (case-insensitively, extension ignored). This is what keeps a cleanup from
     * silently stealing `magnet:` back from another handler the user chose.
     */
    fun isOwnHandler(command: String, executableName: String): Boolean {
        val program = command.trim().let { raw ->
            if (raw.startsWith("\"")) raw.substring(1).substringBefore('"') else raw.substringBefore(' ')
        }
        val programName = program.substringAfterLast('\\').substringAfterLast('/').removeSuffix(".exe")
        return programName.equals(executableName, ignoreCase = true)
    }

    /** Whether the program a registered open command launches is gone (an uninstalled Blaze). */
    fun isMissingTarget(command: String): Boolean {
        val program = command.trim().let { raw ->
            if (raw.startsWith("\"")) raw.substring(1).substringBefore('"') else raw.substringBefore(' ')
        }
        val path = runCatching { java.nio.file.Paths.get(program) }.getOrNull() ?: return false
        return path.isAbsolute && !java.nio.file.Files.exists(path)
    }

    /** Reads the current registration; null when the scheme is unregistered (or off Windows). */
    private fun queryOpenCommand(scheme: String): String? {
        if (!isWindowsHost() || !scheme.matches(SAFE_SCHEME)) return null
        val output = runRegCapturing(
            listOf("reg", "query", "HKCU\\Software\\Classes\\$scheme\\shell\\open\\command", "/ve"),
        ) ?: return null
        return parseOpenCommand(output)
    }

    /**
     * Removes Blaze's own `HKCU\Software\Classes\<scheme>` registration - the counterpart of
     * [ensureRegistered], and the piece no MSI uninstall hook performs because jpackage never knew the
     * key existed.
     *
     * It refuses to touch a handler that is not ours ([isOwnHandler]), and in [staleOnly] mode also
     * refuses a handler whose program still exists: an app that is installed and registered is exactly
     * what the user asked for, and its magnet links work. `staleOnly` is what a startup self-heal uses;
     * the explicit uninstall cleanup passes `false` to drop a live registration as well.
     */
    fun unregister(
        scheme: String,
        executableName: String,
        staleOnly: Boolean,
        isWindows: Boolean = isWindowsHost(),
    ): Boolean {
        if (!isWindows) return false
        val current = queryOpenCommand(scheme) ?: return true // nothing registered: already clean
        if (!isOwnHandler(current, executableName)) {
            logger.info("Leaving the {}:// handler alone; it launches '{}', which is not Blaze's own registration.", scheme, current)
            return false
        }
        if (staleOnly && !isMissingTarget(current)) {
            logger.debug("Keeping the {}:// handler: '{}' is still installed.", scheme, current)
            return false
        }
        return runReg(buildUnregisterCommand(scheme))
    }

    private fun runReg(args: List<String>): Boolean = try {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val finished = process.waitFor(REG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            logger.warn("{} timed out", args.first())
            false
        } else {
            process.exitValue() == 0
        }
    } catch (e: Exception) {
        logger.warn("Failed to run {}", args.first(), e)
        false
    }

    /** Like [runReg] but keeps stdout, so a value can be read back; null when the query could not run. */
    private fun runRegCapturing(args: List<String>): String? = try {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(REG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            logger.warn("{} timed out", args.first())
            null
        } else {
            output
        }
    } catch (e: Exception) {
        logger.debug("Failed to run {}", args.first(), e)
        null
    }

    fun isWindowsHost(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    private val OPEN_COMMAND_VALUE = Regex("^\\s*\"?\\(Default\\)\"?\\s+REG_[A-Z_]+\\s+(.*)$", RegexOption.IGNORE_CASE)
}
