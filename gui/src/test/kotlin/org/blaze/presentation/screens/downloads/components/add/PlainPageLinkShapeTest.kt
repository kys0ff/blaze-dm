package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shape test that decides whether an unhandled link is worth warning about.
 *
 * Guarded here: a link that reads as a *page* must not be downloaded as one silently, and a link
 * that obviously names a file must not cost the user a confirmation click for nothing.
 */
class PlainPageLinkShapeTest {

    @Test
    fun `roots and extension-less routes read as pages`() {
        assertTrue(looksLikeWebPageLink("https://example.com"))
        assertTrue(looksLikeWebPageLink("https://example.com/"))
        assertTrue(looksLikeWebPageLink("https://example.com/share/AbCd123"))
        assertTrue(looksLikeWebPageLink("https://example.com/download?id=5"))
        assertTrue(looksLikeWebPageLink("https://example.com/page#top"))
        assertTrue(looksLikeWebPageLink("https://example.com/index.html"))
        assertTrue(looksLikeWebPageLink("https://example.com/f.php"))
    }

    @Test
    fun `file-shaped links are left alone`() {
        assertFalse(looksLikeWebPageLink("https://example.com/file.zip"))
        assertFalse(looksLikeWebPageLink("https://example.com/a/b/ubuntu-24.04.iso?token=xyz"))
        assertFalse(looksLikeWebPageLink("https://example.com/linux.torrent"))
        assertFalse(looksLikeWebPageLink("https://cdn.example.com/video.mp4"))
    }

    @Test
    fun `an unparseable link is not guessed at`() {
        // Garbage input is already covered by the unsupported-source hint and by the probe itself.
        assertFalse(looksLikeWebPageLink("not a url at all"))
    }

    @Test
    fun `only an http page link is warned about`() {
        val state = AddDownloadState("/home/user/Downloads")

        state.url = TextFieldValue("https://example.com/share/AbCd123")
        assertTrue(state.looksLikeWebPage)

        state.url = TextFieldValue("magnet:?xt=urn:btih:abc&dn=ubuntu")
        assertFalse(state.looksLikeWebPage)

        state.url = TextFieldValue("https://example.com/file.zip")
        assertFalse(state.looksLikeWebPage)
    }

    @Test
    fun `a batch list is judged per entry, not as a page`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            url = TextFieldValue("12 links")
            batchItems.add(BatchDownloadItem("https://example.com/share/AbCd123"))
        }

        assertFalse(state.looksLikeWebPage)
    }

    @Test
    fun `editing the source drops a pending confirmation`() {
        val state = AddDownloadState("/home/user/Downloads").apply {
            pendingPlainPageSource = "https://example.com/share/AbCd123"
        }

        state.resetMetadata()

        assertNull(state.pendingPlainPageSource)
    }
}
