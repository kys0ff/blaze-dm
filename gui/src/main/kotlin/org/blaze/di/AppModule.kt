package org.blaze.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.search.SearchController
import org.koin.dsl.module

val appModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { AppCommandBus() }
    // One window-level search brain; the title-bar icon, the hamburger item and Ctrl/Cmd+K all drive it.
    single { SearchController(get()) }
}
