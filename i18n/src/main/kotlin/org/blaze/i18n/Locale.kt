package org.blaze.i18n

enum class BlazeLocale(val code: String, val displayName: String) {
    English("en", "English")
}

fun getStrings(locale: BlazeLocale): BlazeStrings = when (locale) {
    BlazeLocale.English -> EnStrings
}
