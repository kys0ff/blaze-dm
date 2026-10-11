package org.blaze.presentation.components.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.Island
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.blaze.presentation.screens.downloads.DetailsViewMode
import org.blaze.presentation.theme.BlazeTheme
import org.jetbrains.jewel.ui.component.Text
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The tool-window host driven through the real composable, over the two things the view modes are
 * actually *for* and that a pure function cannot show:
 *
 * - an unpinned (or undocked) pane folds away when the click lands outside it, and *only* then — the
 *   same click on a pinned or floated pane does nothing;
 * - a docked pane reserves a share of the frame while an overlaying pane leaves the content its full
 *   width, which is exactly the difference between Dock Pinned and Dock Unpinned.
 *
 * The content and the pane are tagged, so every assertion reads the tree the user sees.
 */
@OptIn(ExperimentalTestApi::class)
class ToolWindowLayoutHostUiTest {

    @Test
    fun `an unpinned pane folds away when the click lands outside it`() {
        var closed = false
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent { HostUnderTest(DetailsViewMode.DOCK_UNPINNED) { closed = true } }
            waitForIdle()
            onNodeWithTag(PANE_TAG).assertIsDisplayed()
            assertTrue(closed.not(), "the pane is open until attention leaves it")

            // A click inside the pane keeps it: landing on it is not leaving it.
            onNodeWithTag(PANE_TAG).performClick()
            waitForIdle()
            assertTrue(closed.not(), "a click on the pane itself must not collapse it")

            // A click on the content it covers is leaving it.
            onNodeWithTag(CONTENT_TAG).performClick()
            waitForIdle()
            assertTrue(closed, "an unpinned pane must collapse when the click misses it")
        }
    }

    @Test
    fun `an undocked pane folds away on an outside click`() {
        var closed = false
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent { HostUnderTest(DetailsViewMode.UNDOCK) { closed = true } }
            waitForIdle()
            onNodeWithTag(CONTENT_TAG).performClick()
            waitForIdle()
            assertTrue(closed, "an undocked pane folds away with the rest of the transient panes")
        }
    }

    @Test
    fun `a floated window stays open through an outside click`() {
        var closed = false
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent { HostUnderTest(DetailsViewMode.FLOAT) { closed = true } }
            waitForIdle()
            onNodeWithTag(CONTENT_TAG).performClick()
            waitForIdle()
            assertTrue(closed.not(), "a floated window stays until the user closes it")
            onNodeWithTag(PANE_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `a pinned pane is not disturbed by a click outside it`() {
        var closed = false
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent { HostUnderTest(DetailsViewMode.DOCK_PINNED) { closed = true } }
            waitForIdle()
            onNodeWithTag(CONTENT_TAG).performClick()
            waitForIdle()
            onNodeWithTag(CONTENT_TAG).performClick()
            waitForIdle()
            assertTrue(closed.not(), "a pinned pane holds its place in the frame")
            onNodeWithTag(PANE_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `an unpinned pane leaves the content its full width while a pinned one takes a share`() {
        lateinit var overlaying: Rect
        lateinit var docked: Rect
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent { HostUnderTest(DetailsViewMode.DOCK_UNPINNED) { } }
            waitForIdle()
            overlaying = onNodeWithTag(CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        }
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent { HostUnderTest(DetailsViewMode.DOCK_PINNED) { } }
            waitForIdle()
            docked = onNodeWithTag(CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        }

        assertTrue(
            overlaying.width > docked.width,
            "unpinned covers the content ($overlaying) instead of reserving a share of it ($docked)",
        )
    }

    @Test
    fun `a floated window takes the corner of the edge it detached from`() {
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent { HostUnderTest(DetailsViewMode.FLOAT) { } }
            waitForIdle()
            val pane = onNodeWithTag(PANE_TAG).fetchSemanticsNode().boundsInRoot
            // The pane is docked to the right edge, so that is the corner it must appear at - not the
            // stale default offset the settings were seeded with.
            assertTrue(pane.top < 700f * 0.25f, "a right-docked window opens at the top ($pane)")
            assertTrue(pane.right > 1000f * 0.6f, "a right-docked window opens against the right edge ($pane)")
        }
    }

    /** The host under test: one tagged pane at the right edge over a tagged content surface. */
    @Composable
    private fun HostUnderTest(
        viewMode: DetailsViewMode,
        onClose: () -> Unit,
    ) {
        BlazeTheme(isDark = true) {
            ToolWindowLayoutHost(
                mainContent = {
                    Island(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize().testTag(CONTENT_TAG)) {
                            Text("the workspace content")
                        }
                    }
                },
                panels = listOf(
                    ToolWindowSpec(
                        id = "details",
                        title = "Details",
                        visible = true,
                        viewMode = viewMode,
                        moveTo = DetailsMoveTo.RIGHT_TOP,
                        splitFraction = BlazeLayout.DETAILS_DEFAULT_LIST_FRACTION,
                        onClose = onClose,
                        content = {
                            Box(modifier = Modifier.fillMaxSize().testTag(PANE_TAG)) {
                                Text("the tool window")
                            }
                        },
                    ),
                ),
            )
        }
    }

    private companion object {
        const val CONTENT_TAG = "host_content"
        const val PANE_TAG = "host_pane"
    }
}
