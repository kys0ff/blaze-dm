package org.blaze.engine.torrent

import bt.Bt
import bt.data.file.FileSystemStorage
import bt.dht.DHTConfig
import bt.dht.DHTModule
import bt.runtime.BtClient
import bt.runtime.BtRuntime
import bt.runtime.Config
import bt.torrent.TorrentSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.blaze.engine.api.*
import org.blaze.engine.core.Downloader
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CancellationException as KotlinCancellationException

class TorrentDownloader(
    private val request: DownloadRequest.Torrent
) : Downloader {
    
    private var client: BtClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun download(): Flow<DownloadTask> = channelFlow {
        val storage = FileSystemStorage(request.destination.toAbsolutePath())
        val config = Config().apply {
            maxPeerConnections = 50
        }
        
        val dhtModule = DHTModule(object : DHTConfig() {
            override fun shouldUseRouterBootstrap(): Boolean = true
        })

        val runtime = BtRuntime.builder(config)
            .autoLoadModules()
            .module(dhtModule)
            .build()

        val clientBuilder = Bt.client(runtime)
            .storage(storage)

        when (val source = request.torrentSource) {
            is TorrentSource.File -> {
                clientBuilder.torrent(source.path.toUri().toURL())
            }
            is TorrentSource.Magnet -> {
                clientBuilder.magnet(source.uri)
            }
        }

        val btClient = clientBuilder.build()
        client = btClient

        val future = btClient.startAsync({ state ->
            scope.launch {
                send(mapStateToTask(state))
            }
        }, 1000)

        try {
            future.join() // Wait for completion or error
            send(mapStateToTask(null, isCompleted = true))
        } catch (e: Exception) {
            if (e is KotlinCancellationException || future.isCancelled) {
                send(mapStateToTask(null, isCancelled = true))
            } else {
                send(mapStateToTask(null, error = DownloadError.NetworkFailure(e.message ?: "Torrent error")))
            }
        } finally {
            btClient.stop()
            runtime.shutdown()
        }
    }

    private fun mapStateToTask(
        state: TorrentSessionState?,
        isCompleted: Boolean = false,
        isCancelled: Boolean = false,
        error: DownloadError? = null
    ): DownloadTask {
        // In Bt 1.10, TorrentSessionState has getDownloaded() and getPiecesTotal()
        // But total bytes requires knowing the torrent metainfo which might be resolving
        val downloaded = state?.downloaded ?: 0L
        val piecesTotal = state?.piecesTotal ?: 0
        val piecesComplete = state?.piecesComplete ?: 0
        
        val progress = if (piecesTotal > 0) piecesComplete.toFloat() / piecesTotal else 0f
        
        val downloadState = when {
            error != null -> DownloadState.Failed
            isCancelled -> DownloadState.Cancelled
            isCompleted -> DownloadState.Completed
            state == null -> DownloadState.Starting
            state.piecesRemaining > 0 -> DownloadState.Downloading
            else -> DownloadState.Seeding
        }

        return DownloadTask(
            id = DownloadId("temp"),
            name = request.name,
            request = request,
            state = downloadState,
            totalBytes = null, // Torrent metainfo might not be available yet
            downloadedBytes = downloaded,
            downloadSpeed = 0,
            uploadSpeed = 0,
            progress = progress,
            error = error,
            createdAt = Instant.now(),
            completedAt = if (isCompleted) Instant.now() else null
        )
    }

    override suspend fun pause() {
        client?.stop()
    }

    override suspend fun cancel() {
        client?.stop()
    }
}
