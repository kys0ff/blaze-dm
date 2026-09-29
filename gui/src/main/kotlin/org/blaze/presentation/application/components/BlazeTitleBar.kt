package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.theme.BlazeColors
import org.blaze.presentation.theme.ambientTitleBarGradientColor
import org.blaze.presentation.theme.blazeAmbientFrame
import org.blaze.presentation.theme.rememberBrandIconAccent
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls

@OptIn(ExperimentalJewelApi::class)
@Composable
fun DecoratedWindowScope.BlazeTitleBar() {
    // Islands: the bar is not a band of its own — it *is* the top of the window frame. The ambient
    // glow (Modifier.blazeAmbientFrame) paints that frame behind it, anchored at the window's very
    // top-left corner, and the title-bar style makes the bar's own background transparent so the
    // glow reads through. Classic: keep Jewel's stock header with the JetBrains brand gradient.
    val gradient = if (BlazeLayout.useIslands) {
        Color.Unspecified
    } else {
        val brandAccent = rememberBrandIconAccent()
        ambientTitleBarGradientColor(state.isActive, brandAccent ?: BlazeColors.accent)
    }
    TitleBar(
        modifier = Modifier
            .blazeAmbientFrame(anchorY = 0.dp)
            .newFullscreenControls(),
        gradientStartColor = gradient,
    ) {
        BlazeTitleBarIdentity()
    }
}
