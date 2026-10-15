package org.blaze.service.mapping

import org.blaze.download.api.DownloadState as PublicState
import org.blaze.engine.api.DownloadState as EngineState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the one boundary allowed to know both state models. The [EngineState] -> [PublicState] mapper is
 * an exhaustive `when`, so this test mainly pins the *meaning* of the new phase mappings and proves every
 * engine state - including the reserved ones - has a deliberate public counterpart rather than a fallback.
 */
class PublicStateMappingTest {

    @Test
    fun `the new and dormant phases map to their intended public names`() {
        assertEquals(PublicState.PROBING, EngineState.Probing.toPublic())
        assertEquals(PublicState.MERGING, EngineState.Merging.toPublic())
        assertEquals(PublicState.COMPLETING, EngineState.Completing.toPublic())
        assertEquals(PublicState.PAUSING, EngineState.Pausing.toPublic())
        assertEquals(PublicState.RESUMING, EngineState.Resuming.toPublic())
        assertEquals(PublicState.VERIFYING, EngineState.Verifying.toPublic())
    }

    @Test
    fun `every engine state maps without falling through`() {
        // `toPublic` has no `else`, so this list only compiles if each state is handled explicitly.
        val engineStates: List<EngineState> = listOf(
            EngineState.Queued, EngineState.Starting, EngineState.Probing, EngineState.Downloading,
            EngineState.Merging, EngineState.Pausing, EngineState.Paused, EngineState.Resuming,
            EngineState.Verifying, EngineState.Completing, EngineState.Completed, EngineState.Seeding,
            EngineState.ResolvingMetadata, EngineState.Failed, EngineState.Cancelled,
        )
        engineStates.forEach { checkNotNull(it.toPublic()) }
    }
}
