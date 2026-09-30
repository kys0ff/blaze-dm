package org.blaze.presentation.screens.downloads.components.add

import org.blaze.resolver.api.ResolvedLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The add-flow's link-pick logic is pure, so the single-vs-batch decision and the metadata that a
 * resolved link can announce without a probe are checked here directly.
 */
class ResolvedLinkSelectionTest {

    @Test
    fun `an empty selection is None`() {
        assertIs<ResolvedPick.None>(pickResolvedLinks(emptyList()))
    }

    @Test
    fun `one selected link continues the single review`() {
        val link = ResolvedLink(directUrl = "https://direct/a", fileName = "a.bin")
        val pick = pickResolvedLinks(listOf(link))
        assertIs<ResolvedPick.Single>(pick)
        assertEquals(link, pick.link)
    }

    @Test
    fun `several selected links expand into a batch`() {
        val links = listOf(
            ResolvedLink(directUrl = "https://direct/a"),
            ResolvedLink(directUrl = "https://direct/b"),
        )
        val pick = pickResolvedLinks(links)
        assertIs<ResolvedPick.Many>(pick)
        assertEquals(links, pick.links)
    }

    @Test
    fun `metadata prefers the handler's name and carries the announced size`() {
        val link = ResolvedLink(directUrl = "https://direct/file.bin", fileName = "shown.zip", sizeBytes = 2048L)
        val metadata = resolvedLinkToMetadata(link)
        assertEquals("shown.zip", metadata.name)
        assertEquals(2048L, metadata.totalSize)
        // A resolved link never ships a torrent-style file list; the single path probes later.
        assertNull(metadata.files)
    }

    @Test
    fun `metadata falls back to the last URL segment when no name is given`() {
        val link = ResolvedLink(directUrl = "https://host/path/report.pdf?token=abc")
        assertEquals("report.pdf", resolvedLinkToMetadata(link).name)
    }

    @Test
    fun `batch items are seeded from each link in order`() {
        val links = listOf(
            ResolvedLink(directUrl = "https://direct/one", fileName = "one.bin"),
            ResolvedLink(directUrl = "https://direct/two", fileName = "two.bin"),
        )
        val items = resolvedLinksToBatchItems(links)
        assertEquals(listOf("https://direct/one", "https://direct/two"), items.map { it.url })
        assertEquals(listOf("one.bin", "two.bin"), items.map { it.metadata?.name })
    }
}
