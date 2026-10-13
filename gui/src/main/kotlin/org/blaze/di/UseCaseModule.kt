package org.blaze.di

import org.blaze.domain.usecase.*
import org.koin.dsl.module

val useCaseModule = module {
    factory { GetDownloadsUseCase(get()) }
    factory { FetchMetadataUseCase(get()) }
    factory { PrepareTorrentUseCase(get()) }
    factory { AddDownloadUseCase(get()) }
    factory { GetDestinationPathUseCase(get()) }
    factory { InspectHlsUseCase(get()) }
    factory { InspectArchiveUseCase(get()) }
    factory { AddDownloadWithSourceUseCase(get()) }
    factory { PauseDownloadUseCase(get()) }
    factory { ResumeDownloadUseCase(get()) }
    factory { CancelDownloadUseCase(get()) }
    factory { RemoveDownloadUseCase(get()) }
    factory { RetryDownloadUseCase(get()) }
    factory { SetPriorityDownloadUseCase(get()) }
    factory { PauseAllDownloadsUseCase(get()) }
    factory { ResumeAllDownloadsUseCase(get()) }
    factory { ClearCompletedDownloadsUseCase(get()) }
    factory { DetectPortableDownloadUseCase(get()) }
    factory { ImportPortableDownloadUseCase(get()) }
}
