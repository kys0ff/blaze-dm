package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import org.blaze.data.AppSettingsRepository
import org.blaze.download.api.*
import org.blaze.i18n.EnStrings
import org.blaze.presentation.application.AppShell
import org.blaze.tray.api.TrayConfig
import org.blaze.tray.api.TrayService
import org.island.shell.islandWindowMenuEntries
import org.island.theme.ui.IslandTheme
import org.island.ui.menu.IslandMenu
import org.island.ui.menu.MenuEntry
import org.island.ui.menu.rememberMenuAnchorAt
import java.nio.file.Files
import kotlin.test.*

/**
 * The title-bar window menu: its rows, their enablement against real window state, and the wiring
 * from each row into [AppShell]'s window commands.
 *
 * Two halves, matching two layers of the feature:
 *  - the pure entry builder [islandWindowMenuEntries] (island-shell) and the shell methods its rows
 *    call, exercised on a real [WindowState] and a real [AppShell] over fake collaborators;
 *  - the menu as rendered through [IslandMenu] (square corners and all), where a disabled row must
 *    survive a click and an enabled one must fire and dismiss, exactly like the IDE's system menu.
 */
@OptIn(ExperimentalTestApi::class)
class WindowSystemMenuTest {

    private val strings = EnStrings

    private fun entries(
        isMaximized: Boolean,
        minimizeEnabled: Boolean = true,
        fired: MutableSet<String>? = null,
    ) = islandWindowMenuEntries(
        restoreLabel = strings.menu.windowRestore,
        minimizeLabel = strings.menu.windowMinimize,
        maximizeLabel = strings.menu.windowMaximize,
        closeLabel = strings.menu.windowClose,
        isMaximized = isMaximized,
        minimizeEnabled = minimizeEnabled,
        onRestore = { fired?.add("Restore") },
        onMinimize = { fired?.add("Minimize") },
        onMaximize = { fired?.add("Maximize") },
        onClose = { fired?.add("Close") },
    )

    // ── row shape and enablement ──────────────────────────────────────────────

    @Test
    fun `the menu reads Restore, Minimize, Maximize, a divider, then Close`() {
        val rows = entries(isMaximized = false)
        assertEquals(5, rows.size)
        assertEquals(
            listOf(strings.menu.windowRestore, strings.menu.windowMinimize, strings.menu.windowMaximize),
            rows.take(3).map { (it as MenuEntry.Action).label },
        )
        assertEquals(MenuEntry.Separator, rows[3])
        assertEquals(strings.menu.windowClose, (rows[4] as MenuEntry.Action).label)
    }

    @Test
    fun `a floating window enables Maximize and disables Restore`() {
        val enabled = entries(isMaximized = false).map { (it as? MenuEntry.Action)?.enabled }
        assertEquals(listOf(false, true, true, null, true), enabled)
    }

    @Test
    fun `a maximized window enables Restore and disables Maximize`() {
        val enabled = entries(isMaximized = true).map { (it as? MenuEntry.Action)?.enabled }
        assertEquals(listOf(true, true, false, null, true), enabled)
    }

    @Test
    fun `an open dialog disables only Minimize`() {
        val enabled = entries(isMaximized = false, minimizeEnabled = false)
            .map { (it as? MenuEntry.Action)?.enabled }
        assertEquals(listOf(false, false, true, null, true), enabled)
    }

    @Test
    fun `Close carries the underlined access key and the other rows carry none`() {
        val actions = entries(isMaximized = false).filterIsInstance<MenuEntry.Action>()
        val close = actions.last()
        assertEquals(0, close.mnemonic, "the first letter of Close is the access key")
        assertEquals('C', strings.menu.windowClose[close.mnemonic!!])
        assertEquals(listOf(null, null, null), actions.take(3).map { it.mnemonic })
    }

    @Test
    fun `each row routes to its own handler`() {
        val fired = linkedSetOf<String>()
        entries(isMaximized = true, fired = fired).filterIsInstance<MenuEntry.Action>().forEach {
            it.onClick()
        }
        assertEquals(setOf("Restore", "Minimize", "Maximize", "Close"), fired)
    }

    // ── the shell behind the rows ─────────────────────────────────────────────

    @Test
    fun `Maximize maximizes the real state and Restore brings back the floating size`() {
        val fixture = ShellFixture()
        val shell = fixture.shell
        assertFalse(shell.isMaximized())

        shell.maximizeWindow()
        assertTrue(shell.isMaximized())

        // What Compose does while maximized: it mirrors the full-screen bounds into state.size.
        shell.windowState.size = DpSize(1920.dp, 1080.dp)

        shell.restoreWindow()
        assertFalse(shell.isMaximized())
        assertEquals(DpSize(1000.dp, 700.dp), shell.windowState.size, "Restore returns the pre-maximize size")
    }

    @Test
    fun `the transitions are inert when the window is already there`() {
        val shell = ShellFixture().shell
        shell.maximizeWindow()
        val maximizedSize = DpSize(1920.dp, 1080.dp)
        shell.windowState.size = maximizedSize

        // A second Maximize must not overwrite the captured floating size with the maximized one.
        shell.maximizeWindow()
        assertTrue(shell.isMaximized())

        shell.restoreWindow()
        assertFalse(shell.isMaximized())
        assertEquals(DpSize(1000.dp, 700.dp), shell.windowState.size)

        // Restore on a floating window changes nothing at all.
        shell.restoreWindow()
        assertEquals(DpSize(1000.dp, 700.dp), shell.windowState.size)
        assertEquals(WindowPlacement.Floating, shell.windowState.placement)
    }

    @Test
    fun `Minimize folds into the tray and takes an open Settings window with it`() {
        val fixture = ShellFixture()
        fixture.shell.settingsOpen.value = true

        fixture.shell.onMinimize()

        assertFalse(fixture.windowVisible.value, "minimize hides the frame into the tray")
        assertFalse(fixture.shell.settingsOpen.value, "the modal must not outlive its hidden owner")
    }

    @Test
    fun `Close runs the frame's close request, which quits when no tray can hold the app`() {
        val fixture = ShellFixture()
        fixture.shell.onCloseRequest()
        assertTrue(fixture.shutdownCalled, "the download service is shut down on the quit path")
        assertTrue(fixture.exited, "exitApplication is what actually closes the window menu's loop")
    }

    // ── the rendered menu ─────────────────────────────────────────────────────

    @Test
    fun `a disabled row survives the click and an enabled Close fires and dismisses`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val open = mutableStateOf(true)
            val fired = linkedSetOf<String>()
            setContent {
                IslandTheme(isDark = true) {
                    Box(Modifier.size(24.dp).padding(8.dp))
                    if (open.value) {
                        // The very surface the title-bar icon pops: square corners, own width.
                        IslandMenu(
                            entries = entries(isMaximized = false, fired = fired),
                            anchor = rememberMenuAnchorAt(Offset(20f, 40f)),
                            onDismissRequest = { open.value = false },
                            unifyWidth = false,
                            squareCorners = true,
                        )
                    }
                }
            }
            waitForIdle()

            // Restore is disabled on a floating window: the IDE keeps the menu open and runs nothing.
            onAllNodesWithText(strings.menu.windowRestore)[0].performClick()
            waitForIdle()
            assertTrue(open.value, "clicking a disabled row must not dismiss the menu")
            assertNull(fired.firstOrNull(), "a disabled row must not fire its handler")

            onAllNodesWithText(strings.menu.windowClose)[0].performClick()
            waitForIdle()
            assertEquals(setOf("Close"), fired)
            assertFalse(open.value, "activating a leaf dismisses the menu")
        }

    /**
     * A real [AppShell] over fake collaborators and a real [WindowState]: the window commands under
     * the menu's rows run against the same state object the frame itself is driven by.
     */
    private class ShellFixture {
        val windowState = WindowState(size = DpSize(1000.dp, 700.dp))
        val windowVisible = mutableStateOf(true)
        var shutdownCalled = false
            private set
        var exited = false
            private set

        val shell: AppShell = AppShell(
            windowState = windowState,
            windowVisible = windowVisible,
            windowRaiseToken = mutableStateOf(0L),
            settingsOpen = mutableStateOf(false),
            appSettingsRepository = AppSettingsRepository(Files.createTempDirectory("blaze-window-menu")),
            trayService = NoTray,
            downloadsApi = FakeApi(),
            exitApplication = { exited = true },
        )

        /** The tray-less environment: [AppShell.onCloseRequest] then takes the quit path. */
        private object NoTray : TrayService {
            override val isSupported = false
            override fun install(config: TrayConfig) = Unit
            override fun setWindowVisible(visible: Boolean) = Unit
            override fun dispose() = Unit
        }

        private inner class FakeApi : DownloadsApi {
            override val capabilities = ApiCapabilities(
                apiVersion = 1,
                supportsHttp = true,
                supportsHttps = true,
                supportsTorrent = true,
                supportsMagnet = true,
                supportsPortableDownloads = false,
                supportsPauseResume = true,
                supportsCancel = true,
                supportsRetry = true,
                supportsScheduling = false,
                supportsEventStream = true,
                bandwidthControl = BandwidthControlSupport.GLOBAL_ONLY,
                prioritySupport = PrioritySupport.EXPLICIT,
                authentication = AuthMode.BEARER_TOKEN,
                requiresLocalhost = true,
            )

            override fun snapshot() = TODO()
            override fun observe() = TODO()
            override fun events() = TODO()
            override suspend fun get(id: String) = TODO()
            override suspend fun create(request: NewDownloadRequest) = TODO()
            override suspend fun start(id: String) = TODO()
            override suspend fun pause(id: String) = TODO()
            override suspend fun resume(id: String) = TODO()
            override suspend fun cancel(id: String) = TODO()
            override suspend fun retry(id: String) = TODO()
            override suspend fun remove(id: String, deleteFiles: Boolean) = TODO()
            override suspend fun setPriority(id: String, priority: DownloadPriority) = TODO()
            override suspend fun probe(request: NewDownloadRequest) = TODO()
            override suspend fun prepareTorrent(url: String, credentials: Map<String, String>) = TODO()
            override suspend fun inspectHls(url: String, variantIndex: Int, credentials: Map<String, String>) = TODO()
            override suspend fun inspectArchive(url: String, credentials: Map<String, String>) = TODO()
            override suspend fun detectPortable(path: String) = TODO()
            override suspend fun importPortable(artifactPath: String, destinationDir: String) = TODO()
            override suspend fun shutdown() {
                shutdownCalled = true
            }
        }
    }
}
