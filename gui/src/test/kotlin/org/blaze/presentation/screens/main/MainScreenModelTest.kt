package org.blaze.presentation.screens.main

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.blaze.data.AppSettingsRepository
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.*
import org.blaze.domain.usecase.GetDownloadsUseCase
import org.blaze.download.api.DownloadPriority
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.screens.extensions.ExtensionsWindowModel
import org.blaze.presentation.screens.serverlog.ServerLogWindowModel
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.PluginLoader
import org.blaze.security.ConsentService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * The shell model's own state is a pure reduce over its events and a projection over the downloads and
 * Extensions-window streams, so [reduceMainUi], [runningDownloads] and [activeToolWindowFor] are checked
 * directly. The thin dispatch in [MainScreenModel] — which event delegates to which collaborator, and how
 * a shell command folds into state — is then verified against the real (temp-dir-backed) collaborators.
 */
class MainScreenModelTest {

    private val tempDir: Path = Files.createTempDirectory("blaze-main-model-test")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun download(id: String, state: DownloadState) = Download(
        id = id,
        name = "file-$id",
        url = "https://host/$id",
        totalSize = 100,
        downloadedSize = 10,
        speed = 100,
        state = state,
        addedAt = 0,
        savePath = "/tmp",
    )

    // ── pure logic ────────────────────────────────────────────────────────────────

    @Test
    fun `running keeps only downloading and queued downloads`() {
        val list = listOf(
            download("a", DownloadState.DOWNLOADING),
            download("b", DownloadState.QUEUED),
            download("c", DownloadState.PAUSED),
            download("d", DownloadState.COMPLETED),
            download("e", DownloadState.SEEDING),
            download("f", DownloadState.FAILED),
        )

        assertEquals(listOf("a", "b"), runningDownloads(list).map { it.id })
    }

    @Test
    fun `the stripe marks Extensions only while its window is raised`() {
        assertEquals(MainToolWindowId.DOWNLOADS, activeToolWindowFor(extensionsVisible = false))
        assertEquals(MainToolWindowId.EXTENSIONS, activeToolWindowFor(extensionsVisible = true))
    }

    @Test
    fun `reduce toggles the about dialog and tasks popup`() {
        val open = reduceMainUi(MainState(), MainEvent.ShowAbout)
        assertTrue(open.aboutVisible)
        assertFalse(reduceMainUi(open, MainEvent.DismissAbout).aboutVisible)

        val expanded = reduceMainUi(MainState(), MainEvent.ToggleTasksPopup)
        assertTrue(expanded.tasksPopupExpanded)
        assertFalse(reduceMainUi(expanded, MainEvent.ToggleTasksPopup).tasksPopupExpanded)
        assertFalse(reduceMainUi(expanded, MainEvent.DismissTasksPopup).tasksPopupExpanded)
    }

    @Test
    fun `reduce leaves ui state untouched for delegating events`() {
        val base = MainState()
        assertEquals(base, reduceMainUi(base, MainEvent.SelectToolWindow(MainToolWindowId.EXTENSIONS)))
        assertEquals(base, reduceMainUi(base, MainEvent.FocusDownload("x")))
    }

    // ── model wiring ──────────────────────────────────────────────────────────────

    private class FakeDownloadRepository(initial: List<Download>) : DownloadRepository {
        val live = MutableStateFlow(initial)
        override val downloads: Flow<List<Download>> get() = live

        override suspend fun fetchMetadata(url: String, credentials: Map<String, String>): DownloadMetadata =
            throw NotImplementedError()

        override suspend fun prepareTorrentFromUrl(url: String, credentials: Map<String, String>): TorrentPreview =
            throw NotImplementedError()

        override suspend fun addDownload(
            url: String, savePath: String, name: String?, fileIndices: List<Int>?, totalSize: Long?,
            files: List<DownloadFile>?, scheduledAt: Long?, priority: DownloadPriority?,
            credentials: Map<String, String>,
        ) = throw NotImplementedError()

        override suspend fun getDestinationPath(url: String, savePath: String, name: String?): String =
            throw NotImplementedError()

        override suspend fun inspectHls(
            url: String, variantIndex: Int, credentials: Map<String, String>,
        ): org.blaze.download.api.HlsInspectionDto = throw NotImplementedError()

        override suspend fun inspectArchive(
            url: String, credentials: Map<String, String>,
        ): org.blaze.download.api.ArchiveInspectionDto = throw NotImplementedError()

        override suspend fun addDownloadWithSource(
            source: org.blaze.download.api.DownloadSource, savePath: String, name: String?,
            totalSize: Long?, scheduledAt: Long?, priority: DownloadPriority?,
            credentials: Map<String, String>,
        ) = throw NotImplementedError()

        override suspend fun pauseDownload(id: String) = throw NotImplementedError()
        override suspend fun resumeDownload(id: String) = throw NotImplementedError()
        override suspend fun cancelDownload(id: String) = throw NotImplementedError()
        override suspend fun removeDownload(id: String, deleteFile: Boolean) = throw NotImplementedError()
        override suspend fun retryDownload(id: String) = throw NotImplementedError()
        override suspend fun setPriority(id: String, priority: DownloadPriority) = throw NotImplementedError()
        override suspend fun pauseAll() = throw NotImplementedError()
        override suspend fun resumeAll() = throw NotImplementedError()
        override suspend fun clearCompleted() = throw NotImplementedError()
        override suspend fun detectPortable(artifactPath: String): PortableInfo = throw NotImplementedError()
        override suspend fun importPortable(artifactPath: String, destinationDir: String): Boolean =
            throw NotImplementedError()
    }

    private class Harness(
        val commandBus: AppCommandBus,
        val repository: FakeDownloadRepository,
        val serverLogWindowModel: ServerLogWindowModel,
        val extensionsWindowModel: ExtensionsWindowModel,
        val model: MainScreenModel,
    )

    private fun harness(startDownloads: List<Download> = emptyList()): Harness {
        val appRepository = AppSettingsRepository(tempDir)
        val resolverDir = Files.createTempDirectory(tempDir, "resolvers")
        val settingsRepository = LinkResolverSettingsRepository(resolverDir)
        val registry = LinkResolverRegistry(
            builtIns = emptyList(),
            pluginLoader = PluginLoader(),
            settingsRepository = settingsRepository,
        )
        val consent = ConsentService(appRepository, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val bus = AppCommandBus()
        val repository = FakeDownloadRepository(startDownloads)
        val serverLog = ServerLogWindowModel(appRepository)
        val extensions = ExtensionsWindowModel(appRepository, registry, settingsRepository, consent)
        val model = MainScreenModel(
            commandBus = bus,
            getDownloads = GetDownloadsUseCase(repository),
            serverLogWindowModel = serverLog,
            extensionsWindowModel = extensions,
        )
        return Harness(bus, repository, serverLog, extensions, model)
    }

    /** The state is an eager combine running on the screen-model scope; poll until it settles. */
    private suspend fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    /**
     * Blocks until the model's eager state combine has produced its first projected value. That proves
     * the screen-model scope is pumping coroutines, so the command-bus collector started in `init` has
     * subscribed too — the bus is a no-replay shared flow, and a command raised before it subscribes is
     * dropped. A marker download makes "combined" distinguishable from the initial seed value.
     */
    private suspend fun warmUp(h: Harness) {
        h.repository.live.value = listOf(download("warmup", DownloadState.DOWNLOADING))
        awaitUntil { h.model.state.value.running.map { it.id } == listOf("warmup") }
    }

    @Test
    fun `state projects running transfers and the resting tool window`() = runBlocking {
        val h = harness(listOf(download("a", DownloadState.DOWNLOADING), download("b", DownloadState.PAUSED)))

        awaitUntil { h.model.state.value.running.map { it.id } == listOf("a") }
        assertEquals(MainToolWindowId.DOWNLOADS, h.model.state.value.activeToolWindow)
    }

    @Test
    fun `a stripe selection raises and lowers the extensions window through its model`() = runBlocking {
        val h = harness()

        h.model.onEvent(MainEvent.SelectToolWindow(MainToolWindowId.EXTENSIONS))
        awaitUntil { h.extensionsWindowModel.state.value.visible }
        awaitUntil { h.model.state.value.activeToolWindow == MainToolWindowId.EXTENSIONS }

        h.model.onEvent(MainEvent.SelectToolWindow(MainToolWindowId.DOWNLOADS))
        awaitUntil { !h.extensionsWindowModel.state.value.visible }
        awaitUntil { h.model.state.value.activeToolWindow == MainToolWindowId.DOWNLOADS }
    }

    @Test
    fun `the About command folds into the same state as the ShowAbout event`() = runBlocking {
        val h = harness()
        warmUp(h)
        assertFalse(h.model.state.value.aboutVisible)

        assertTrue(h.commandBus.send(AppCommand.ShowAbout))
        awaitUntil { h.model.state.value.aboutVisible }
    }

    @Test
    fun `the Server Log command toggles the log window through its own model`() = runBlocking {
        val h = harness()
        warmUp(h)
        assertFalse(h.serverLogWindowModel.state.value.visible)

        assertTrue(h.commandBus.send(AppCommand.ShowServerLog))
        awaitUntil { h.serverLogWindowModel.state.value.visible }
    }

    @Test
    fun `a task focus leaves as a one-shot jump effect`() = runBlocking {
        val h = harness()
        val received = mutableListOf<MainEffect>()
        val collector = launch { h.model.effects.collect { received.add(it) } }
        warmUp(h)

        h.model.onEvent(MainEvent.FocusDownload("dl-1"))
        awaitUntil { received.isNotEmpty() }

        val jumps = received.filterIsInstance<MainEffect.JumpToDownload>().map { it.id }
        assertEquals(listOf("dl-1"), jumps)
        collector.cancel()
    }
}
