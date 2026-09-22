package org.blaze.cli

import org.blaze.download.api.ApiCapabilities
import org.blaze.download.api.DownloadDto
import org.blaze.download.api.DownloadState

/**
 * Human-readable rendering of API responses for terminal users.
 *
 * Deliberately separate from [org.blaze.cli.BlazeApiClient]'s raw JSON passthrough: scripts use `--json`
 * and never parse this. The layout is compact one-line-per-download so `blaze list` stays scannable, with
 * a fuller block for `blaze info`.
 */
object HumanOutput {

    fun bytes(n: Long): String {
        if (n < 0) return "-"
        val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
        var value = n.toDouble()
        var idx = 0
        while (value >= 1024 && idx < units.lastIndex) {
            value /= 1024
            idx++
        }
        return if (idx == 0) "$n B" else String.format("%.1f %s", value, units[idx])
    }

    fun speed(n: Long): String = if (n <= 0) "-" else "${bytes(n)}/s"

    private fun eta(seconds: Long?): String = when {
        seconds == null -> "-"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "%dm %02ds".format(seconds / 60, seconds % 60)
        else -> "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
    }

    fun progressBar(dto: DownloadDto, width: Int = 22): String {
        val frac = dto.progress
            ?: dto.totalBytes?.let { if (it > 0) (dto.downloadedBytes.toFloat() / it) else null }
            ?: when (dto.state) {
                DownloadState.COMPLETED, DownloadState.SEEDING -> 1f
                else -> 0f
            }
        val filled = (frac.coerceIn(0f, 1f) * width).toInt()
        return "█".repeat(filled) + "░".repeat(width - filled) + " %3d%%".format((frac * 100).toInt())
    }

    fun renderList(dtos: List<DownloadDto>): String = buildString {
        if (dtos.isEmpty()) {
            appendLine("(no downloads)")
            return@buildString
        }
        for (dto in dtos) {
            appendLine("#${dto.id.take(8)}  ${dto.name}  [${dto.state}]")
            appendLine("    ${progressBar(dto)}  ${bytes(dto.downloadedBytes)}${dto.totalBytes?.let { " / ${bytes(it)}" } ?: ""}  ${speed(dto.speedBytesPerSecond)}  ETA ${eta(dto.etaSeconds)}")
        }
    }

    fun renderInfo(dto: DownloadDto): String = buildString {
        appendLine("Id:        ${dto.id}")
        appendLine("Name:      ${dto.name}")
        appendLine("State:     ${dto.state}")
        appendLine("Source:    ${dto.source.kind} ${dto.source.locator}")
        appendLine("Destination: ${dto.destination}")
        appendLine("Progress:  ${progressBar(dto)}")
        appendLine("Size:      ${bytes(dto.downloadedBytes)}${dto.totalBytes?.let { " / ${bytes(it)}" } ?: " (unknown total)"}")
        appendLine("Speed:     ${speed(dto.speedBytesPerSecond)}   avg ${speed(dto.averageSpeedBytesPerSecond ?: 0)}")
        appendLine("ETA:       ${eta(dto.etaSeconds)}")
        if (dto.peers > 0) appendLine("Peers:     ${dto.peers}")
        appendLine("Resumable: ${dto.resumable}")
        dto.error?.let { appendLine("Error:     ${it.code}: ${it.message}") }
    }

    fun renderStatus(capabilities: ApiCapabilities, reachable: Boolean): String = buildString {
        appendLine("Service:   ${if (reachable) "online" else "offline"}")
        appendLine("API v${capabilities.apiVersion}   auth: ${capabilities.authentication}")
        fun flag(name: String, ok: Boolean) = appendLine("  %-22s %s".format(name, if (ok) "yes" else "no"))
        flag("HTTP/HTTPS", capabilities.supportsHttp && capabilities.supportsHttps)
        flag("Torrent/Magnet", capabilities.supportsTorrent && capabilities.supportsMagnet)
        flag("Portable downloads", capabilities.supportsPortableDownloads)
        flag("Pause/Resume", capabilities.supportsPauseResume)
        flag("Cancel", capabilities.supportsCancel)
        flag("Retry", capabilities.supportsRetry)
        flag("Event stream", capabilities.supportsEventStream)
        appendLine("  bandwidth control:   ${capabilities.bandwidthControl}")
        appendLine("  priority support:    ${capabilities.prioritySupport}")
    }
}
