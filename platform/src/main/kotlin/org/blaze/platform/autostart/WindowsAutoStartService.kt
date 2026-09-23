package org.blaze.platform.autostart

import org.blaze.platform.api.PlatformIdentity
import java.util.concurrent.TimeUnit

/**
 * Windows autostart via the per-user registry `Run` key, driven through `reg.exe` —
 * no registry bindings needed. `disable` treats "value already absent" as success.
 *
 * The value is re-readable through [registrationCommand] so a login item left behind by an uninstall
 * (or by a debug run pointing at a JDK that has since been updated away) can be dropped rather than
 * failing at every sign-in.
 */
class WindowsAutoStartService(
    private val identity: PlatformIdentity,
    private val execCommand: () -> String = {
        ProcessHandle.current().info().command().orElse("${identity.executableName}.exe")
    }
) : AutoStartService {

    override val isSupported: Boolean = true

    private fun isEnabledQuietly(): Boolean =
        runCatching { run("reg", "query", REG_KEY, "/v", identity.appName).exitCode == 0 }.getOrDefault(false)

    override fun isEnabled(): Boolean = isEnabledQuietly()

    override fun registrationCommand(): String? {
        if (!isWindowsHost()) return null // never shell out to reg.exe on another platform
        val result = runCatching { run("reg", "query", REG_KEY, "/v", identity.appName) }.getOrNull() ?: return null
        if (result.exitCode != 0) return null
        return parseRunValue(result.output, identity.appName)
    }

    override fun enable(): Result<Unit> = runCatching {
        val command = requireLaunchableLoginItem(execCommand())
        val result = run("reg", "add", REG_KEY, "/v", identity.appName, "/t", "REG_SZ", "/d", command, "/f")
        check(result.exitCode == 0) { "reg add failed (exit ${result.exitCode}): ${result.output}" }
    }

    override fun disable(): Result<Unit> = runCatching {
        val result = run("reg", "delete", REG_KEY, "/v", identity.appName, "/f")
        // Non-zero usually means the value was never there; confirm and accept that.
        check(result.exitCode == 0 || !isEnabledQuietly()) {
            "reg delete failed (exit ${result.exitCode}): ${result.output}"
        }
    }

    private data class RegResult(val exitCode: Int, val output: String)

    private fun run(vararg command: String): RegResult {
        val process = ProcessBuilder(command.toList()).apply { redirectErrorStream(true) }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(15, TimeUnit.SECONDS)) { "Registry command timed out: ${command.joinToString()}" }
        return RegResult(process.exitValue(), output)
    }

    internal companion object {
        private const val REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

        // `reg query` pads the name with spaces and the name itself may contain them (identity.appName
        // is arbitrary text), so the type token - not the whitespace run - is what ends the name.
        private val RUN_VALUE = Regex("^\\s*(.+?)\\s+REG_[A-Z_]+\\s+(.*)$")

        /**
         * Pulls [valueName] out of `reg query` output
         * (`    Blaze    REG_SZ    C:\Program Files\Blaze\bin\Blaze.exe`). Null when the line is
         * absent, which is also how "ERROR: ... unable to find ..." output reads - no value, nothing
         * to prune.
         */
        fun parseRunValue(output: String, valueName: String): String? = output.lineSequence()
            .mapNotNull { line -> RUN_VALUE.matchEntire(line)?.groupValues?.let { it[1] to it[2] } }
            .firstOrNull { (name, _) -> name.equals(valueName, ignoreCase = true) }
            ?.second
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        private fun isWindowsHost(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")
    }
}
