package org.blaze.di

import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.http.KtorHttpDownloader
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.torrent.TorrentDownloader
import org.koin.dsl.module
import java.nio.file.Path

val engineModule = module {
    single {
        val userHome = System.getProperty("user.home")
        val path = Path.of(userHome, ".blaze")
        DownloadRepository(path)
    }

    single<DownloadEngine> {
        DownloadManager(
            scope = get(),
            repository = get(),
            httpDownloaderFactory = { KtorHttpDownloader(it) },
            torrentDownloaderFactory = { TorrentDownloader(it) }
        )
    }
}
