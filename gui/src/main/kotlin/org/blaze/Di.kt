package org.blaze

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.blaze.data.DownloadRepositoryImpl
import org.blaze.domain.repository.DownloadRepository
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.http.KtorHttpDownloader
import org.blaze.engine.torrent.TorrentDownloader
import java.nio.file.Path

object Di {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val engineRepository by lazy {
        val userHome = System.getProperty("user.home")
        val path = Path.of(userHome, ".blaze")
        org.blaze.engine.persistence.DownloadRepository(path)
    }

    val engine by lazy {
        DownloadManager(
            scope = scope,
            repository = engineRepository,
            httpDownloaderFactory = { KtorHttpDownloader(it) },
            torrentDownloaderFactory = { TorrentDownloader(it) }
        )
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepositoryImpl(engine, scope)
    }
}
