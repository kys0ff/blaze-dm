package org.blaze.engine.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.PortableTransferKind
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.portable.PortableFormat
import org.blaze.engine.portable.PortableImport
import org.blaze.engine.portable.PortableKind
import org.blaze.engine.portable.PortableManifest
import org.blaze.engine.portable.PortableStore
import org.blaze.engine.portable.PortableTorrentFile
import org.blaze.engine.portable.TorrentPortableFile
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.storage.DefaultFileStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the engine's detect + import wiring for a *moved* artifact reconstructs a live, resumable
 * download without the originating machine's database or sidecars. The transfer itself is covered by
 * [org.blaze.engine.portable.HttpPortableResumeTest]; here a mock executor keeps the assertions on
 * exactly what the import put on disk and into the queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortableImportTest {

    private class MockExecutor : DownloadExecutor {
        override fun execute(): Flow<org.blaze.engine.api.DownloadTask> = kotlinx.coroutines.flow.emptyFlow()
    }

    private val storage = DefaultFileStorage()

    private fun manager(storageDir: Path, scope: CoroutineScope) = DownloadManager(
        scope = scope,
        repository = DownloadRepository(storageDir),
        settingsRepository = EngineSettingsRepository(storageDir),
        storage = storage,
        executorFactory = { MockExecutor() }
    )

    private fun httpArtifact(partial: Path, total: Long, doneChunks: Int): PortableManifest.Http {
        Files.write(partial, ByteArray(total.toInt()))
        val manifest = PortableManifest.Http(
            originalUrl = "https://host.example/big.iso",
            resolvedUrl = null,
            contentLength = total,
            chunkSize = 256L * 1024,
            chunkCount = (total / (256L * 1024)).toInt(),
            acceptsRanges = true,
            validator = "\"abc\"",
            completedChunks = BitSet().apply { for (i in 0 until doneChunks) set(i) }
        )
        PortableStore(partial).create(PortableKind.HTTP, total, PortableFormat.HTTP_SLOT_SIZE, manifest)
        return manifest
    }

    @Test
    fun `detects a moved HTTP partial by content, not name`() = runTest {
        val temp = Files.createTempDirectory("blaze-import-http")
        val artifact = temp.resolve("renamed-arbitrarily.dat") // no .part suffix — extension is not proof
        val total = 2L * 1024 * 1024
        httpArtifact(artifact, total, doneChunks = 4)

        val info = assertNotNull(PortableImport.detect(artifact))
        assertEquals(PortableTransferKind.HTTP, info.kind)
        assertEquals(total, info.totalBytes)
        assertEquals(artifact.fileName.toString(), info.suggestedName)
        assertEquals("https://host.example/big.iso", info.source)
        assertTrue(info.resumable)
        assertTrue(info.availableBytes > 0)
        temp.toFile().deleteRecursively()
    }

    @Test
    fun `import moves the partial into place and rebuilds the resume sidecar`() = runTest {
        val temp = Files.createTempDirectory("blaze-import-http2")
        val storageDir = temp.resolve("storage").also { Files.createDirectories(it) }
        val artifactDir = temp.resolve("incoming").also { Files.createDirectories(it) }
        val destDir = temp.resolve("downloads").also { Files.createDirectories(it) }
        val artifact = artifactDir.resolve("big.iso")
        val total = 2L * 1024 * 1024
        httpArtifact(artifact, total, doneChunks = 4)

        val manager = manager(storageDir, backgroundScope)
        try {
            val id = assertNotNull(manager.importPortableDownload(artifact, destDir))
            val task = assertNotNull(manager.getTask(id))
            val request = assertIs<DownloadRequest.Http>(task.request)
            assertEquals("https://host.example/big.iso", request.url)
            assertEquals(total, task.totalBytes)

            // The artifact has been relocated to the canonical partial beside the chosen destination.
            val finalFile = destDir.resolve("big.iso")
            val partial = storage.getPartialFile(finalFile).toPath()
            assertEquals(finalFile, request.destination)
            assertTrue(Files.exists(partial), "import must stage the partial at the canonical path")
            assertTrue(!Files.exists(artifact), "import must move, not duplicate, the artifact")
            assertTrue(
                Files.exists(storage.getResumeStateFile(finalFile).toPath()),
                "import must rebuild the same-machine resume sidecar from the embedded manifest"
            )
        } finally {
            manager.shutdown()
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `import a torrent from its embedded metainfo without the original torrent file`() = runTest {
        val temp = Files.createTempDirectory("blaze-import-torrent")
        val storageDir = temp.resolve("storage").also { Files.createDirectories(it) }
        // A moved torrent folder: content file + the marker, and NO original .torrent anywhere.
        val folder = temp.resolve("moved").also { Files.createDirectories(it) }
        Files.writeString(folder.resolve("movie.mkv"), "partial content")
        val metainfo = ByteArray(128) { (it * 7 + 3).toByte() }
        val manifest = PortableManifest.Torrent(
            name = "Movie",
            torrentFile = metainfo,
            contentLength = 4L * 1024 * 1024 * 1024,
            pieceLength = 16384,
            pieceCount = 262144,
            trackers = listOf("http://tracker/announce"),
            files = listOf(PortableTorrentFile("movie.mkv", 4L * 1024 * 1024 * 1024)),
            completedPieces = BitSet().apply { set(0); set(1) },
            multiFile = false
        )
        TorrentPortableFile.write(TorrentPortableFile.markerPath(folder), manifest, generation = 1)

        // Detection works from a file *inside* the folder, walking up to the marker.
        val info = assertNotNull(PortableImport.detect(folder.resolve("movie.mkv")))
        assertEquals(PortableTransferKind.TORRENT, info.kind)
        assertEquals("Movie", info.suggestedName)
        assertTrue(info.resumable)

        val manager = manager(storageDir, backgroundScope)
        try {
            val id = assertNotNull(manager.importPortableDownload(folder, folder))
            val task = assertNotNull(manager.getTask(id))
            val request = assertIs<DownloadRequest.Torrent>(task.request)
            assertEquals(folder, request.destination)
            val source = assertIs<TorrentSource.File>(request.torrentSource)
            assertTrue(Files.isRegularFile(source.path), "import must materialise a torrent from the embedded metainfo")
            assertContentEquals(metainfo, Files.readAllBytes(source.path), "the exact original metainfo must be restored")
        } finally {
            manager.shutdown()
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a non-portable file is not imported`() = runTest {
        val temp = Files.createTempDirectory("blaze-import-none")
        val storageDir = temp.resolve("storage").also { Files.createDirectories(it) }
        val plain = temp.resolve("photo.jpg").also { Files.write(it, ByteArray(4096) { (it % 251).toByte() }) }
        val manager = manager(storageDir, backgroundScope)
        try {
            assertEquals(null, PortableImport.detect(plain))
            assertEquals(null, manager.importPortableDownload(plain, temp))
        } finally {
            manager.shutdown()
            temp.toFile().deleteRecursively()
        }
    }
}
