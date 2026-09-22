package org.blaze.service.mapping

import org.blaze.download.api.DownloadState as PublicState
import org.blaze.engine.api.DownloadState as EngineState

/**
 * Translates the engine's internal state hierarchy into the public, serializable [PublicState] enum.
 *
 * This is the one place allowed to know both state models. The mapping is total (every engine state has
 * a public counterpart), so adding a new engine state is a compile error here rather than a silent gap
 * that leaves a client guessing.
 */
fun EngineState.toPublic(): PublicState = when (this) {
    EngineState.Queued -> PublicState.QUEUED
    EngineState.Starting -> PublicState.STARTING
    EngineState.Downloading -> PublicState.DOWNLOADING
    EngineState.Pausing -> PublicState.PAUSING
    EngineState.Paused -> PublicState.PAUSED
    EngineState.Resuming -> PublicState.RESUMING
    EngineState.Verifying -> PublicState.VERIFYING
    EngineState.ResolvingMetadata -> PublicState.RESOLVING_METADATA
    EngineState.Completed -> PublicState.COMPLETED
    EngineState.Seeding -> PublicState.SEEDING
    EngineState.Failed -> PublicState.FAILED
    EngineState.Cancelled -> PublicState.CANCELLED
}
