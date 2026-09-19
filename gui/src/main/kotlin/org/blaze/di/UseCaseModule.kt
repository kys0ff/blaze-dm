package org.blaze.di

import org.blaze.domain.usecase.*
import org.koin.dsl.module

val useCaseModule = module {
    factory { GetDownloadsUseCase(get()) }
    factory { FetchMetadataUseCase(get()) }
    factory { AddDownloadUseCase(get()) }
    factory { GetDestinationPathUseCase(get()) }
    factory { PauseDownloadUseCase(get()) }
    factory { ResumeDownloadUseCase(get()) }
    factory { CancelDownloadUseCase(get()) }
    factory { RemoveDownloadUseCase(get()) }
    factory { RetryDownloadUseCase(get()) }
    factory { PauseAllDownloadsUseCase(get()) }
    factory { ResumeAllDownloadsUseCase(get()) }
    factory { ClearCompletedDownloadsUseCase(get()) }
}
