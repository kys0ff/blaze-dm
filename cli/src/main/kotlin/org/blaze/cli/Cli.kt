package org.blaze.cli

import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Parses `blaze` arguments and executes a command against the running downloader service.
 *
 * Kept as a pure function over injectable streams so the tests can drive every command, exit code and
 * output mode without spawning a process. It performs no downloading itself - it is a thin client of the
 * same public API the GUI and a future browser bridge use.
 */
class Cli(
    private val clientFactory: (BlazeApiClient.Endpoint) -> BlazeApiClient = { BlazeApiClient(it) },
) {

    fun run(rawArgs: Array<out String>, out: PrintStream, err: PrintStream): Int {
        val args = rawArgs.toList()
        val parsed = parse(args)
        val global = parsed.flags
        val json = global.contains("--json")
        val quiet = global.contains("--quiet")

        val command = parsed.positionals.firstOrNull()
        if (command == null || command == "help" || global.contains("--help")) {
            printUsage(out)
            return if (command == null) ExitCodes.INVALID_ARGUMENTS else ExitCodes.SUCCESS
        }

        val storageDir = parsed.opts["--storage-dir"]?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".blaze")

        return try {
            val endpoint = BlazeApiClient.discover(
                storageDir = storageDir,
                urlOverride = parsed.opts["--url"],
                tokenOverride = parsed.opts["--token"],
            )
            val client = clientFactory(endpoint)
            dispatch(command, parsed, client, out, err, json, quiet)
        } catch (e: BlazeApiClient.CliError) {
            if (!quiet) err.println("blaze: ${e.message}")
            e.exitCode
        } catch (e: IllegalArgumentException) {
            if (!quiet) err.println("blaze: ${e.message}")
            ExitCodes.INVALID_ARGUMENTS
        } catch (e: Exception) {
            if (!quiet) err.println("blaze: unexpected error: ${e.message}")
            ExitCodes.GENERAL_FAILURE
        }
    }

    private fun dispatch(
        command: String,
        parsed: ParsedArgs,
        client: BlazeApiClient,
        out: PrintStream,
        err: PrintStream,
        json: Boolean,
        quiet: Boolean,
    ): Int {
        val positionalArgs = parsed.positionals.drop(1)
        when (command) {
            "download", "torrent" -> {
                val source = positionalArgs.firstOrNull()
                    ?: throw IllegalArgumentException("missing source (URL, magnet or torrent path)")
                val dest = parsed.opts["--output"] ?: parsed.opts["-o"]
                    ?: Paths.get(System.getProperty("user.home"), "Downloads").toString()
                val (key, value) = when {
                    command == "torrent" && !source.startsWith("magnet:") -> "torrentFile" to source
                    command == "torrent" || source.startsWith("magnet:") -> "magnet" to source
                    else -> "url" to source
                }
                val resp = client.create(
                    sourceKey = key,
                    sourceValue = value,
                    destination = dest,
                    filename = parsed.opts["--filename"],
                    connections = parsed.opts["--connections"]?.toIntOrNull(),
                    bandwidth = parsed.opts["--bandwidth"]?.toLongOrNull(),
                    autoStart = !parsed.flags.contains("--no-start"),
                    referer = parsed.opts["--referer"],
                    userAgent = parsed.opts["--user-agent"],
                )
                return emitObject(resp, out, err, json, quiet) { dto ->
                    if (!quiet) out.println("Added download #${dto.id.take(8)} (${dto.state})")
                }
            }

            "list" -> {
                val resp = client.list()
                return emitArray(resp, out, json) { dtos -> out.print(HumanOutput.renderList(dtos)) }
            }

            "info" -> {
                val id = requireId(positionalArgs)
                val resp = client.info(id)
                return emitObject(resp, out, err, json, quiet) { dto -> out.print(HumanOutput.renderInfo(dto)) }
            }

            "pause", "resume", "cancel", "retry" -> {
                val id = requireId(positionalArgs)
                val verb = if (command == "resume") "resume" else command
                val resp = client.action(id, verb)
                return emitOk(resp, out, err, json, quiet, "$command ok")
            }

            "remove" -> {
                val id = requireId(positionalArgs)
                val resp = client.remove(id, deleteFiles = parsed.flags.contains("--delete-files"))
                return emitOk(resp, out, err, json, quiet, "remove ok")
            }

            "status" -> {
                val caps = client.capabilities()
                if (json) {
                    out.println(caps.body)
                    return if (caps.status in 200..299) ExitCodes.SUCCESS else ExitCodes.SERVICE_UNAVAILABLE
                }
                out.print(HumanOutput.renderStatus(client.decodeCapabilities(caps.body), caps.status in 200..299))
                return ExitCodes.SUCCESS
            }

            "wait" -> {
                val id = requireId(positionalArgs)
                return waitFor(client, id, out, err, json)
            }

            else -> {
                err.println("blaze: unknown command '$command'")
                printUsage(err)
                return ExitCodes.INVALID_ARGUMENTS
            }
        }
    }

    private fun waitFor(client: BlazeApiClient, id: String, out: PrintStream, err: PrintStream, json: Boolean): Int {
        var terminal: String? = null
        // First, is it already done?
        val current = runCatching { client.info(id) }.getOrNull()
        if (current != null && current.status in 200..299) {
            val dto = client.decodeDto(current.body)
            if (dto.state.isTerminal) {
                terminal = dto.state.name
                return finishWait(client, id, dto.state, out, json)
            }
        }
        client.streamEvents { event ->
            val eventId = event["downloadId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val type = event["type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            if (eventId == id && (type == "completed" || type == "failed" || type == "cancelled")) {
                terminal = type
                true
            } else {
                false
            }
        }
        return when (terminal) {
            "completed" -> finishWait(client, id, org.blaze.download.api.DownloadState.COMPLETED, out, json)
            "failed" -> finishWait(client, id, org.blaze.download.api.DownloadState.FAILED, out, json)
            "cancelled" -> finishWait(client, id, org.blaze.download.api.DownloadState.CANCELLED, out, json)
            else -> {
                err.println("blaze: event stream ended before download $id finished")
                ExitCodes.GENERAL_FAILURE
            }
        }
    }

    private fun finishWait(
        client: BlazeApiClient,
        id: String,
        state: org.blaze.download.api.DownloadState,
        out: PrintStream,
        json: Boolean,
    ): Int {
        val resp = client.info(id)
        if (json) out.println(resp.body) else if (resp.status in 200..299) {
            out.println("Download $id finished: $state")
        }
        return when (state) {
            org.blaze.download.api.DownloadState.COMPLETED -> ExitCodes.SUCCESS
            org.blaze.download.api.DownloadState.FAILED -> ExitCodes.DOWNLOAD_FAILED
            else -> ExitCodes.DOWNLOAD_FAILED
        }
    }

    private fun emitObject(
        resp: BlazeApiClient.Response,
        out: PrintStream,
        err: PrintStream,
        json: Boolean,
        quiet: Boolean,
        render: (org.blaze.download.api.DownloadDto) -> Unit,
    ): Int {
        if (json) {
            out.println(resp.body)
        } else if (resp.status in 200..299) {
            render(decodeDto(resp.body))
        } else {
            if (!quiet) err.println("blaze: ${errorText(resp.body)}")
            return ExitCodes.GENERAL_FAILURE
        }
        return if (resp.status in 200..299) ExitCodes.SUCCESS else ExitCodes.GENERAL_FAILURE
    }

    private val codec = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun decodeDto(body: String): org.blaze.download.api.DownloadDto =
        codec.decodeFromString(org.blaze.download.api.DownloadDto.serializer(), body)

    private fun emitArray(
        resp: BlazeApiClient.Response,
        out: PrintStream,
        json: Boolean,
        render: (List<org.blaze.download.api.DownloadDto>) -> Unit,
    ): Int {
        if (resp.status !in 200..299) return ExitCodes.SERVICE_UNAVAILABLE
        if (json) out.println(resp.body)
        else render(codec.decodeFromString(kotlinx.serialization.builtins.ListSerializer(org.blaze.download.api.DownloadDto.serializer()), resp.body))
        return ExitCodes.SUCCESS
    }

    private fun emitOk(
        resp: BlazeApiClient.Response,
        out: PrintStream,
        err: PrintStream,
        json: Boolean,
        quiet: Boolean,
        message: String,
    ): Int {
        if (resp.status !in 200..299) {
            if (!quiet) err.println("blaze: ${errorText(resp.body)}")
            return if (resp.status == 404) ExitCodes.GENERAL_FAILURE else ExitCodes.GENERAL_FAILURE
        }
        if (json) out.println(resp.body) else if (!quiet) out.println(message)
        return ExitCodes.SUCCESS
    }

    private fun errorText(body: String): String = runCatching {
        val error = kotlinx.serialization.json.Json.parseToJsonElement(body)
            .let { (it as? kotlinx.serialization.json.JsonObject)?.get("error") as? kotlinx.serialization.json.JsonObject }
        (error?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "request failed"
    }.getOrDefault("request failed")

    private fun requireId(args: List<String>): String =
        args.getOrNull(0) ?: throw IllegalArgumentException("missing <id>")

    private fun printUsage(out: PrintStream) {
        out.println(
            """
            blaze - command-line client for the Blaze downloader service

            Usage: blaze <command> [args] [options]

            Commands:
              download <url>            Queue an HTTP/HTTPS download
              torrent <magnet|file>     Queue a torrent (magnet URI or .torrent path)
              list                      List all downloads
              info <id>                 Show one download in detail
              pause <id>                Pause a download
              resume <id>               Resume a paused/failed download
              cancel <id>               Cancel a download
              retry <id>                Retry a failed download
              remove <id>               Remove a download (--delete-files also removes data)
              status                    Show service health and capabilities
              wait <id>                 Block until the download reaches a terminal state

            Download options:
              -o, --output DIR         Destination directory (default ~/Downloads)
                  --filename NAME      Override the file/folder name
                  --connections N      Parallel HTTP connections
                  --bandwidth BYTES    Per-transfer speed ceiling (bytes/sec)
                  --no-start            Queue without starting
                  --referer URL         HTTP Referer to send
                  --user-agent UA       HTTP User-Agent to send

            Global options:
                  --json               Machine-readable JSON output (for scripts)
                  --quiet              Suppress human progress messages
                  --verbose            Extra diagnostics
                  --url URL            Explicit service base URL (overrides discovery)
                  --token TOKEN        Explicit bearer token (overrides discovery)
                  --storage-dir DIR    Blaze data dir for discovery (default ~/.blaze)

            Exit codes: 0 ok, 1 error, 2 bad args, 3 service unavailable, 4 download failed, 5 auth failure
            """.trimIndent()
        )
    }

    private data class ParsedArgs(
        val positionals: List<String>,
        val opts: Map<String, String>,
        val flags: Set<String>,
    )

    private companion object {
        private val VALUE_OPTS = setOf(
            "--output", "-o", "--filename", "--connections", "--bandwidth", "--referer", "--user-agent",
            "--url", "--token", "--storage-dir",
        )
        private val BOOL_FLAGS = setOf("--json", "--quiet", "--verbose", "--no-start", "--delete-files", "--help")

        fun parse(args: List<String>): ParsedArgs {
            val positionals = mutableListOf<String>()
            val opts = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    VALUE_OPTS.contains(a) && i + 1 < args.size -> { opts[a] = args[i + 1]; i += 2 }
                    BOOL_FLAGS.contains(a) -> { flags += a; i++ }
                    a.startsWith("--") -> { i++ } // ignore unknown long options
                    else -> { positionals += a; i++ }
                }
            }
            return ParsedArgs(positionals, opts, flags)
        }
    }
}
