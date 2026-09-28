package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.theme.ambientTitleBarGradientColor
import org.blaze.presentation.theme.rememberBrandIconAccent
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls

@OptIn(ExperimentalJewelApi::class)
@Composable
fun DecoratedWindowScope.BlazeTitleBar() {
    // The ambient glow follows the brand color pulled from the app icon; the theme accent takes
    // over until (or unless) that extraction is available.
    val brandAccent = rememberBrandIconAccent()
    TitleBar(
        modifier = Modifier.newFullscreenControls(),
        gradientStartColor = ambientTitleBarGradientColor(state.isActive, brandAccent ?: BlazeColors.accent),
    ) {
        BlazeTitleBarIdentity()
    }
}
