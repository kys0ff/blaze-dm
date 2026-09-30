package org.blaze.presentation.search

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.EnStrings
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.theme.BlazeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Search Everywhere, driven through the real popup.
 *
 * These are the tests that *would have failed before the fix*: the title-bar icon and the hamburger
 * item used to raise a "focus the in-list filter" command with no popup behind it, so clicking search
 * visibly did nothing. Here the same command is shown to open the window-level popup, and the popup is
 * then exercised the way a user would — type to filter, Enter to activate (a download hands off to the
 * screen's activator, an action re-raises its command on the shared bus), Escape to dismiss, the tabs to
 * scope, and the two empty states ("Type to search" / "Nothing found").
 *
 * The host below mirrors [org.blaze.presentation.application.components.BlazeWindow]'s collector so the
 * command → popup path tested here is literally the path the app composes.
 */
@OptIn(ExperimentalTestApi::class)
class SearchEverywherePopupTest {

    private val popupTag = "search_everywhere_popup"

    private fun download(id: String, name: String) = Download(
        id = id,
        name = name,
        url = "https://example.com/$name",
        totalSize = 1_000L,
        downloadedSize = 500L,
        speed = 0L,
        state = DownloadState.PAUSED,
        addedAt = 0L,
        savePath = "/home/user/Downloads/$name",
    )

    /**
     * Composes the popup plus the window-level wiring around it: the bus collector that toggles on
     * [AppCommand.SearchEverywhere] (exactly BlazeWindow's job) and a recorder of every command seen, so
     * a test can tell "the popup opened" from "an action fired its command".
     */
    private fun ComposeUiTest.host(
        controller: SearchController,
        bus: AppCommandBus,
        received: MutableList<AppCommand>,
    ) {
        setContent {
            BlazeTheme(isDark = true) {
                LaunchedEffect(bus, controller) {
                    bus.commands.collect { command ->
                        received += command
                        if (command == AppCommand.SearchEverywhere) controller.toggle()
                    }
                }
                SearchEverywherePopup(controller)
            }
        }
        waitForIdle()
    }

    // The island's testTag sits in the unmerged tree, so the finder must opt into it explicitly.
    private fun ComposeUiTest.popupShown(): Boolean =
        onAllNodes(hasTestTag(popupTag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.type(text: String) {
        onNode(hasSetTextAction()).performTextInput(text)
        waitForIdle()
    }

    // Keys are injected on the text field (the popup's focus owner); unconsumed Enter/Escape propagate
    // up to the Popup's onKeyEvent, which is exactly the handler the app installs.
    private fun ComposeUiTest.press(key: Key) {
        onNode(hasSetTextAction()).performKeyInput {
            keyDown(key)
            keyUp(key)
        }
        waitForIdle()
    }

    @Test
    fun `the search command opens the popup`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val bus = AppCommandBus()
        val controller = SearchController(bus)
        val received = mutableListOf<AppCommand>()
        host(controller, bus, received)

        assertFalse(popupShown(), "the popup starts closed")

        bus.send(AppCommand.SearchEverywhere)
        waitForIdle()

        assertTrue(popupShown(), "the title-bar icon / Ctrl+K command must open Search Everywhere")
    }

    @Test
    fun `typing filters the download results live`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val bus = AppCommandBus()
        val controller = SearchController(bus)
        controller.submitDownloads(listOf(download("a", "report-2024.pdf"), download("b", "wallpaper.png")))
        host(controller, bus, mutableListOf())

        controller.open(tab = SearchTab.Downloads)
        waitForIdle()

        onNodeWithText("report-2024.pdf").assertExists()
        onNodeWithText("wallpaper.png").assertExists()

        type("report")

        onNodeWithText("report-2024.pdf").assertExists()
        assertFalse(
            onAllNodes(hasTestTag("search_result_b"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            "a non-matching download must drop out as the query filters",
        )
    }

    @Test
    fun `enter activates the highlighted download`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val bus = AppCommandBus()
        val controller = SearchController(bus)
        var activated: String? = null
        controller.registerDownloadActivator { activated = it }
        controller.submitDownloads(listOf(download("a", "report-2024.pdf")))
        host(controller, bus, mutableListOf())

        controller.open(tab = SearchTab.Downloads)
        waitForIdle()
        type("report")

        press(Key.Enter)

        assertEquals("a", activated, "Enter must hand the download to the screen's activator")
        assertFalse(popupShown(), "activating a result closes the popup")
    }

    @Test
    fun `enter on an action re-raises its command on the bus`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val bus = AppCommandBus()
        val controller = SearchController(bus)
        val received = mutableListOf<AppCommand>()
        host(controller, bus, received)

        controller.open(tab = SearchTab.Actions)
        waitForIdle()

        // With a blank query the Actions tab lists the whole searchable registry; the first row is New
        // Download, so Enter must re-raise exactly that command (the same one the hamburger menu fires).
        press(Key.Enter)

        assertTrue(
            received.contains(AppCommand.NewDownload),
            "activating an action must send its command, saw $received",
        )
        assertFalse(popupShown(), "activating an action closes the popup")
    }

    @Test
    fun `escape closes the popup`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val bus = AppCommandBus()
        val controller = SearchController(bus)
        host(controller, bus, mutableListOf())

        controller.open()
        waitForIdle()
        assertTrue(popupShown())

        press(Key.Escape)

        assertFalse(popupShown(), "Escape must dismiss the popup")
        assertFalse(controller.isOpen)
    }

    @Test
    fun `clicking a tab scopes the results`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val bus = AppCommandBus()
        val controller = SearchController(bus)
        host(controller, bus, mutableListOf())

        controller.open()
        waitForIdle()

        onNodeWithText(EnStrings.search.tabSettings).performClick()
        waitForIdle()

        assertEquals(SearchTab.Settings, controller.tab, "clicking a tab selects it")
    }

    @Test
    fun `an empty query on the all tab invites typing`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val bus = AppCommandBus()
        val controller = SearchController(bus)
        host(controller, bus, mutableListOf())

        controller.open()
        waitForIdle()

        onNodeWithText(EnStrings.search.typeToSearch).assertExists()
    }

    @Test
    fun `a query with no matches reports nothing found`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val bus = AppCommandBus()
        val controller = SearchController(bus)
        controller.submitDownloads(listOf(download("a", "report-2024.pdf")))
        host(controller, bus, mutableListOf())

        controller.open(tab = SearchTab.All)
        waitForIdle()
        type("zzzzqqq")

        onNodeWithText(EnStrings.search.nothingFound).assertExists()
    }

    @Test
    fun `opening from settings defaults to the settings tab`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val bus = AppCommandBus()
        val controller = SearchController(bus)
        host(controller, bus, mutableListOf())

        controller.open(fromSettings = true)
        waitForIdle()

        assertEquals(SearchTab.Settings, controller.tab, "Search Everywhere from Settings starts on Settings")
    }
}
