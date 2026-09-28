package org.blaze.i18n

/**
 * Locales Blaze ships with. [displayName] is always an endonym (English, العربية): the language
 * picker must stay readable no matter which language is currently active.
 */
enum class BlazeLocale(val code: String, val displayName: String) {
    English("en", "English"),
    Arabic("ar", "العربية")
}

/** Whether this locale's typography flows right-to-left. */
val BlazeLocale.isRtl: Boolean get() = this == BlazeLocale.Arabic

fun getStrings(locale: BlazeLocale): BlazeStrings = when (locale) {
    BlazeLocale.English -> EnStrings
    BlazeLocale.Arabic -> ArStrings
}
