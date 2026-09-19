package org.blaze.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface LocaleManager {
    val currentLocale: StateFlow<BlazeLocale>
    fun setLocale(locale: BlazeLocale)
}

class LocaleManagerImpl : LocaleManager {
    private val _currentLocale = MutableStateFlow(BlazeLocale.English)
    override val currentLocale = _currentLocale.asStateFlow()

    override fun setLocale(locale: BlazeLocale) {
        _currentLocale.value = locale
    }
}
