package org.blaze.platform.autostart

import java.nio.file.Files
import java.nio.file.Path

/**
 * macOS autostart via a per-user LaunchAgent plist with `RunAtLoad`, written to
 * `~/Library/LaunchAgents/org.blaze.autostart.plist`.
 */
class MacOsAutoStartService(
    private val launchAgentsDir: Path = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents"),
    private val execCommand: () -> String = {
        ProcessHandle.current().info().command().orElse("blaze")
    }
) : AutoStartService {

    private val plistFile = launchAgentsDir.resolve("org.blaze.autostart.plist")

    override val isSupported: Boolean = true

    override fun isEnabled(): Boolean = Files.exists(plistFile)

    override fun enable(): Result<Unit> = runCatching {
        Files.createDirectories(launchAgentsDir)
        Files.writeString(plistFile, plist(execCommand()))
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
        appendLine("\t<string>org.blaze.autostart</string>")
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
}
