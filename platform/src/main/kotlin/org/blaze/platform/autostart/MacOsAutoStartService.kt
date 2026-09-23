package org.blaze.platform.autostart

import org.blaze.platform.api.PlatformIdentity
import java.nio.file.Files
import java.nio.file.Path

/**
 * macOS autostart via a per-user LaunchAgent plist with `RunAtLoad`, written to
 * `~/Library/LaunchAgents/<identity.launchAgentLabel>.plist`.
 *
 * The plist survives both the debug session that could have written it and a DMG that has long since
 * been unmounted, so [registrationCommand] reads the recorded program back: launchd otherwise keeps
 * trying (and failing quietly) at every login.
 */
class MacOsAutoStartService(
    private val identity: PlatformIdentity,
    private val launchAgentsDir: Path = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents"),
    private val execCommand: () -> String = {
        ProcessHandle.current().info().command().orElse(identity.executableName)
    }
) : AutoStartService {

    private val plistFile = launchAgentsDir.resolve("${identity.launchAgentLabel}.plist")

    override val isSupported: Boolean = true

    override fun isEnabled(): Boolean = Files.exists(plistFile)

    override fun registrationCommand(): String? =
        runCatching { parseProgramArguments(Files.readString(plistFile)) }.getOrNull()

    override fun enable(): Result<Unit> = runCatching {
        val exec = requireLaunchableLoginItem(execCommand())
        Files.createDirectories(launchAgentsDir)
        Files.writeString(plistFile, plist(exec))
    }

    override fun disable(): Result<Unit> = runCatching {
        Files.deleteIfExists(plistFile)
    }.map { }

    private fun plist(exec: String): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")
        appendLine("\t<key>Label</key>")
        appendLine("\t<string>${identity.launchAgentLabel}</string>")
        appendLine("\t<key>ProgramArguments</key>")
        appendLine("\t<array>")
        appendLine("\t\t<string>${xmlEscape(exec)}</string>")
        appendLine("\t</array>")
        appendLine("\t<key>RunAtLoad</key>")
        appendLine("\t<true/>")
        appendLine("</dict>")
        appendLine("</plist>")
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    internal companion object {
        private val FIRST_STRING = Regex("<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        /**
         * The executable a `ProgramArguments` array launches: its first `<string>` entry, unescaped.
         * Null when the plist has no such array (a hand-edited or foreign file).
         */
        fun parseProgramArguments(plist: String): String? = plist
            .substringAfter("<key>ProgramArguments</key>", missingDelimiterValue = "")
            .let(FIRST_STRING::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.xmlUnescape()
            ?.takeIf { it.isNotBlank() }

        private fun String.xmlUnescape(): String = this
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
