package org.blaze.platform.autostart

import java.util.concurrent.TimeUnit

/**
 * Windows autostart via the per-user registry `Run` key, driven through `reg.exe` —
 * no registry bindings needed. `disable` treats "value already absent" as success.
 */
class WindowsAutoStartService(
    private val execCommand: () -> String = {
        ProcessHandle.current().info().command().orElse("blaze.exe")
    }
) : AutoStartService {

    override val isSupported: Boolean = true

    private fun isEnabledQuietly(): Boolean =
        runCatching { run("reg", "query", REG_KEY, "/v", APP_VALUE).exitCode == 0 }.getOrDefault(false)

    override fun isEnabled(): Boolean = isEnabledQuietly()

    override fun enable(): Result<Unit> = runCatching {
        val result = run("reg", "add", REG_KEY, "/v", APP_VALUE, "/t", "REG_SZ", "/d", execCommand(), "/f")
        check(result.exitCode == 0) { "reg add failed (exit ${result.exitCode}): ${result.output}" }
    }

    override fun disable(): Result<Unit> = runCatching {
        val result = run("reg", "delete", REG_KEY, "/v", APP_VALUE, "/f")
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

    private companion object {
        const val REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val APP_VALUE = "Blaze"
    }
}
