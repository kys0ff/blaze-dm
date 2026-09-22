package org.blaze.engine.portable

import kotlinx.serialization.json.Json
import org.blaze.engine.persistence.HttpResumeState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Bridges a portable HTTP artifact that has just been moved onto this machine back into the
 * engine's normal resume flow.
 *
 * The embedded [PortableManifest.Http] carries the exact chunk layout and completion bitmap the
 * originating machine had. Rewriting it as the same-machine sidecar lets the existing, validator-
 * checked [org.blaze.engine.execution.HttpDownloadCoordinator] resume path take over untouched: it
 * re-probes the URL, refuses the state if the remote resource changed, and otherwise fetches only
 * the missing chunks. The sidecar is again the same-machine authority; the embedded region remains
 * the cross-machine one and keeps being checkpointed as chunks land.
 *
 * Only the *resume* state is recreated here — never any credential. If PC B needs authentication it
 * supplies it from its own configuration when the download is (re)queued, exactly as a fresh
 * download would.
 *
 * @return true when [artifact] held a usable HTTP manifest and the sidecar was written.
 */
fun installHttpResumeSidecar(artifact: PortableArtifact, sidecarPath: Path): Boolean {
    val manifest = artifact.manifest as? PortableManifest.Http ?: return false
    val state = HttpResumeState.of(
        totalBytes = manifest.contentLength,
        chunkSize = manifest.chunkSize,
        validator = manifest.validator,
        completed = manifest.completedChunks
    )
    return runCatching {
        sidecarPath.parent?.let { Files.createDirectories(it) }
        val tmp = sidecarPath.resolveSibling("${sidecarPath.fileName}.tmp")
        Files.writeString(tmp, Json.encodeToString(HttpResumeState.serializer(), state))
        runCatching {
            Files.move(tmp, sidecarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            if (it is java.nio.file.AtomicMoveNotSupportedException ||
                it.cause is java.nio.file.AtomicMoveNotSupportedException
            ) {
                Files.move(tmp, sidecarPath, StandardCopyOption.REPLACE_EXISTING)
            } else throw it
        }
    }.isSuccess
}
