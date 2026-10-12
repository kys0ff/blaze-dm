package org.blaze

/**
 * Blaze's own version, the single literal the UI and the compatibility gates read.
 *
 * Kept in sync with `gui/build.gradle.kts`'s `version` (there is no generated BuildConfig in this
 * module). [org.blaze.resolver.core.PluginLoader] compares it against a manifest's
 * `minAppVersion` to reject handler jars that declare a newer Blaze than the one running.
 */
const val APP_VERSION: String = "1.0.0"
