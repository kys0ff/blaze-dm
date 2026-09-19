package org.blaze.di

import org.blaze.data.DownloadRepositoryImpl
import org.blaze.domain.repository.DownloadRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<DownloadRepository> {
        DownloadRepositoryImpl(
            engine = get(),
            scope = get()
        )
    }
}
