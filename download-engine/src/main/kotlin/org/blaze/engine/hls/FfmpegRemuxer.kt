package org.blaze.engine.hls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * [MediaRemuxer] backed by an externally-installed ffmpeg binary. This is **not** a compile-time or
 * runtime dependency: Blaze never bundles ffmpeg. The binary is located via [executable] (PATH lookup or
 * an explicit path from settings), and [isAvailable] probes for it once before any download attempts a
 * remux. If the binary is absent the engine falls back to the honest "unsupported" outcome exactly as if
 * this class did not exist.
 *
 * The remux itself uses ffmpeg's concat demuxer with stream copy (`-c copy`): no transcoding, no quality
 * loss, just a container-level remux from the downloaded segment files into a single playable file.
 * The operation is bounded by a configurable timeout so a hung child process cannot park a download
 * coroutine forever.
 *
 * @param executable path to the ffmpeg binary; defaults to `"ffmpeg"` (relies on the OS PATH).
 * @param remuxTimeoutSeconds maximum wall-clock seconds to wait for ffmpeg before killing it.
 */
class FfmpegRemuxer(
    private val executable: String = "ffmpeg",
    private val remuxTimeoutSeconds: Long = DEFAULT_REMUX_TIMEOUT_SECONDS,
) : MediaRemuxer {

    private val logger = LoggerFactory.getLogger(FfmpegRemuxer::class.java)

    override fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder(executable, "-version")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            val finished = process.waitFor(AVAILABILITY_PROBE_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun remux(parts: List<Path>, destination: Path): RemuxOutcome {
        return withContext(Dispatchers.IO) {
            if (parts.isEmpty()) return@withContext RemuxOutcome.Failure("no segments to remux")

            // Write a concat-demuxer list file (one `file '<path>'` line per segment).
            val listFile: Path = try {
                val list = Files.createTempFile("blaze-hls-concat", ".txt")
                val sb = StringBuilder()
                for (part in parts) {
                    // ffmpeg concat demuxer single-quote escaping: ' → '\''
                    val abs = part.toAbsolutePath().toString().replace("'", "'\\''")
                    sb.append("file '").append(abs).append("'\n")
                }
                Files.writeString(list, sb.toString(), StandardCharsets.UTF_8)
                list
            } catch (e: IOException) {
                return@withContext RemuxOutcome.Failure("cannot write concat list: ${e.message}")
            }

            // Write to a temp sibling so we never leave a half-remuxed destination behind.
            val tmp = destination.resolveSibling("${destination.fileName}.remuxing")

            try {
                val cmd = listOf(
                    executable,
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.toString(),
                    "-c", "copy",
                    tmp.toString(),
                )

                val process = try {
                    ProcessBuilder(cmd).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    return@withContext RemuxOutcome.Failure("ffmpeg not available: ${e.message}")
                }

                // Drain stdout/stderr to a bounded buffer so the child never blocks on a full pipe.
                val output = ByteArrayOutputStream(MAX_DIAGNOSTIC_BYTES)
                val reader = Thread {
                    runCatching {
                        process.inputStream.use { it.copyTo(output) }
                    }
                }.apply { isDaemon = true; name = "ffmpeg-output-reader" }
                reader.start()

                val finished = process.waitFor(remuxTimeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    reader.join(2_000)
                    return@withContext RemuxOutcome.Failure("ffmpeg timed out after ${remuxTimeoutSeconds}s")
                }
                reader.join(5_000)

                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    val log = output.toString(StandardCharsets.UTF_8).take(MAX_DIAGNOSTIC_BYTES).trim()
                    runCatching { Files.deleteIfExists(tmp) }
                    return@withContext RemuxOutcome.Failure(
                        "ffmpeg remux failed (exit $exitCode)${if (log.isNotEmpty()) ": $log" else ""}"
                    )
                }

                if (!Files.exists(tmp) || Files.size(tmp) == 0L) {
                    runCatching { Files.deleteIfExists(tmp) }
                    return@withContext RemuxOutcome.Failure("ffmpeg produced no output file")
                }

                // Atomic within the same directory.
                Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING)
                logger.info("ffmpeg remux succeeded: {} segments -> {}", parts.size, destination)
                RemuxOutcome.Success
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(tmp) }
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                    RemuxOutcome.Failure("remux interrupted")
                } else {
                    RemuxOutcome.Failure("remux error: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                runCatching { Files.deleteIfExists(listFile) }
            }
        }
    }

    private companion object {
        const val AVAILABILITY_PROBE_SECONDS = 5L
        const val DEFAULT_REMUX_TIMEOUT_SECONDS = 600L
        const val MAX_DIAGNOSTIC_BYTES = 4096
    }
}
