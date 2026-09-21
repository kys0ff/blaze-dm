package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls

@OptIn(ExperimentalJewelApi::class)
@Composable
fun DecoratedWindowScope.BlazeTitleBar() {
    TitleBar(
        modifier = Modifier.newFullscreenControls(),
    ) {
        BlazeTitleBarIdentity()
    }
}
