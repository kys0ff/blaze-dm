package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import org.blaze.presentation.screens.main.MainScreen
import org.blaze.presentation.theme.blazeAmbientBodyFrame

/**
 * The window body: everything below the title bar.
 *
 * In the Islands shell this node *is* the frame layer — it paints the flat frame color with the
 * ambient glow anchored at the window's top-left corner (offset by the title bar's height, so the
 * glow is one continuous field with the strip behind the bar above it) and bleeding up over the bar's
 * transparent 1dp border row, so no pixel of that row is left to the window's raw background. Screens
 * then float on it as islands and only the gaps leak the glow; that is why [MainScreen] must not lay
 * its own opaque background here.
 */
@Composable
fun BlazeContent() {
    Box(modifier = Modifier.fillMaxSize().blazeAmbientBodyFrame()) {
        Navigator(MainScreen())
    }
}
