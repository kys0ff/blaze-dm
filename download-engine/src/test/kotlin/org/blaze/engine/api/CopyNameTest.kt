package org.blaze.engine.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The marker that says "this is the second copy", pinned because two layers read it: the one that
 * applies it when a name is taken, and the torrent's metadata step, which has to recognise it so a
 * later, better name from the metainfo does not erase the disambiguation.
 */
class CopyNameTest {

    @Test
    fun `a copy is numbered from one`() {
        assertEquals("Big.Buck (1)", CopyName.of("Big.Buck", 1))
        assertEquals("Big.Buck (12)", CopyName.of("Big.Buck", 12))
    }

    @Test
    fun `a numbered copy reads back as the copy it is`() {
        assertEquals(1, CopyName.attemptOf("Big.Buck (1)", "Big.Buck"))
        assertEquals(7, CopyName.attemptOf("Big.Buck (7)", "Big.Buck"))
    }

    @Test
    fun `anything that is not a copy of that name is not a copy`() {
        assertNull(CopyName.attemptOf("Big.Buck", "Big.Buck"), "the original is not a copy of itself")
        assertNull(CopyName.attemptOf("Big.Bunny (1)", "Big.Buck"), "a copy of another name")
        assertNull(CopyName.attemptOf("Big.Buck (1).torrent", "Big.Buck"), "the marker has to be the whole tail")
        assertNull(CopyName.attemptOf("Big.Buck", "Big.Buck (1)"), "the direction matters")
    }

    @Test
    fun `a parenthesis that does not count is just part of a name`() {
        assertNull(CopyName.attemptOf("Big.Buck ()", "Big.Buck"))
        assertNull(CopyName.attemptOf("Big.Buck (zero)", "Big.Buck"))
        assertNull(CopyName.attemptOf("Big.Buck (-1)", "Big.Buck"))
        assertNull(CopyName.attemptOf("Big.Buck (1.5)", "Big.Buck"))
        assertEquals(1, CopyName.attemptOf("Big.Buck (01)", "Big.Buck"), "a padded count is still a count")
    }
}
