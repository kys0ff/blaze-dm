package org.blaze.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalBlazeStrings = staticCompositionLocalOf<BlazeStrings> {
    EnStrings
}

val blazeStrings: BlazeStrings
    @Composable
    @ReadOnlyComposable
    get() = LocalBlazeStrings.current
