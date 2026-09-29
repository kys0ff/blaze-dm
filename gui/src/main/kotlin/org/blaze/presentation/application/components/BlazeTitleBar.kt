package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.blaze.presentation.components.BlazeLayout
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
    // Islands: the bar is a flat frame color (its background comes from the title-bar style in
    // BlazeTheme), so no ambient gradient. Classic: keep the JetBrains-style brand glow.
    val gradient = if (BlazeLayout.useIslands) {
        Color.Unspecified
    } else {
        val brandAccent = rememberBrandIconAccent()
        ambientTitleBarGradientColor(state.isActive, brandAccent ?: BlazeColors.accent)
    }
    TitleBar(
        modifier = Modifier.newFullscreenControls(),
        gradientStartColor = gradient,
    ) {
        BlazeTitleBarIdentity()
    }
}
