package org.blaze.i18n

import org.koin.dsl.module

val i18nModule = module {
    single<LocaleManager> { LocaleManagerImpl() }
}
