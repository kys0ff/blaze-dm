package org.blaze.osintegration.platform

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
     * refused write) - callers treat this as best-effort desktop polish, never a startup blocker.
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

    fun isWindowsHost(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")
}
