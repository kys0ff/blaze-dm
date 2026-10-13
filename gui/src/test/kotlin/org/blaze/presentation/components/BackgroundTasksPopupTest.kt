package org.blaze.presentation.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.EnStrings
import org.island.theme.ui.IslandTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The status-bar Background Tasks popup, driven through the real composable.
 *
 * These cover what the popup is *for*: it lists every live transfer, a row click hands that download to
 * the same activator Search Everywhere uses (select + open details, then dismiss), and it closes with an
 * honest active/queued total. Everything the card shows is real state — there is no placeholder row.
 */
@OptIn(ExperimentalTestApi::class)
class BackgroundTasksPopupTest {

    private fun download(
        id: String,
        name: String,
        state: DownloadState,
        speed: Long = 0L,
        eta: Long? = null,
    ) = Download(
        id = id,
        name = name,
        url = "https://example.com/$name",
        totalSize = 1_000L,
        downloadedSize = 400L,
        speed = speed,
        eta = eta,
        state = state,
        addedAt = 0L,
        savePath = "/home/user/Downloads/$name",
    )

    private fun rowTag(id: String) = "background_task_$id"

    @Test
    fun `a running download is listed and clicking it opens its details`() =
        runDesktopComposeUiTest(width = 560, height = 420) {
            val running = listOf(
                download("a", "movie.mkv", DownloadState.DOWNLOADING, speed = 5_000L, eta = 90L),
                download("b", "album.zip", DownloadState.QUEUED),
            )
            var opened: String? = null

            setContent {
                IslandTheme(isDark = true) {
                    BackgroundTasksPopup(
                        running = running,
                        onPause = {},
                        onCancel = {},
                        onOpenDetails = { opened = it },
                        onDismiss = {},
                    )
                }
            }
            waitForIdle()

            onNodeWithText("movie.mkv").assertExists()
            onNodeWithText("album.zip").assertExists()

            // Each live transfer gets its own tagged row surface.
            onNode(hasTestTag(rowTag("b")), useUnmergedTree = true).assertExists()

            // Clicking the row's text area selects + opens details for exactly that download.
            onNode(hasTestTag(rowTag("a")), useUnmergedTree = true).performClick()
            assertEquals("a", opened, "a row click must open that download's details")

            // The footer reports the active / queued split across the whole list.
            onNodeWithText(EnStrings.downloads.backgroundTasks.footer(active = 1, queued = 1)).assertExists()
        }

    @Test
    fun `an empty running list shows the empty message`() =
        runDesktopComposeUiTest(width = 560, height = 420) {
            setContent {
                IslandTheme(isDark = true) {
                    BackgroundTasksPopup(
                        running = emptyList(),
                        onPause = {},
                        onCancel = {},
                        onOpenDetails = {},
                        onDismiss = {},
                    )
                }
            }
            waitForIdle()

            onNodeWithText(EnStrings.downloads.backgroundTasks.empty).assertExists()
            assertTrue(
                onAllNodes(hasTestTag("background_tasks_popup"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty(),
                "the popup hosts a tagged surface",
            )
        }
}
