package org.blaze.engine.execution

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The metainfo's name usually wins, because the source often could not say what the torrent is called.
 * Pinned here are both halves of that: the guess the metainfo corrects, and the one name it must leave
 * alone - the numbered folder Blaze chose when the name it wanted was already taken.
 */
class TorrentDisplayNameTest {

    private val downloads = Path.of("/home/user/Downloads")

    @Test
    fun `the metainfo replaces a name the source had to guess`() {
        val destination = downloads.resolve("tt1234567")

        assertEquals("Big.Buck.Bunny", displayNameFor(destination, "Big.Buck.Bunny"))
    }

    @Test
    fun `a disambiguated folder keeps its number`() {
        val destination = downloads.resolve("Big.Buck (1)")

        assertEquals(
            "Big.Buck (1)",
            displayNameFor(destination, "Big.Buck"),
            "the row has to name the folder the bytes are actually going into",
        )
    }

    @Test
    fun `a folder named after something else is still the metainfo's business`() {
        val destination = downloads.resolve("My Copy")

        assertEquals("Big.Buck", displayNameFor(destination, "Big.Buck"))
    }
}
