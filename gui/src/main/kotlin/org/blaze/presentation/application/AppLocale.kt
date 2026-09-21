package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.LocaleManager
import org.blaze.i18n.getStrings
import org.koin.compose.koinInject

/**
 * Localized strings for the current app locale. Recomposed only when the user
 * changes the locale, so callers can safely use the result as a `remember` key.
 */
@Composable
fun rememberBlazeStrings(): BlazeStrings {
    val localeManager = koinInject<LocaleManager>()
    val currentLocale by localeManager.currentLocale.collectAsState()
    return remember(currentLocale) { getStrings(currentLocale) }
}
